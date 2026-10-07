import { memo, useCallback, useMemo, useRef, useState, type FocusEvent, type KeyboardEvent, type MouseEvent } from "react";
import type { SeatCoordinate } from "../types/seatmap";
import { button, FOCUS_RING, ui } from "./ui";
import { hasMultipleSections, seatLabel, seatSection } from "./seatLabel";

interface Props {
  /** 원본 이미지 픽셀 크기 — 좌표 기준이자 SVG viewBox */
  imageWidth: number;
  imageHeight: number;
  seats: SeatCoordinate[];
  selectedUid?: string | null;
  onSelectSeat: (seat: SeatCoordinate) => void;
}

/**
 * 좌석 좌표(원본 이미지 픽셀)를 SVG <rect>로 그린다. 원본 이미지는 서버에 없으므로 좌석 사각형만 그린다.
 * 판매 상태 색 구분은 하지 않는다 — 선택됨/선택안됨만 구분한다 (react-conventions 스킬).
 * 반응형: viewBox가 비율을 유지하며 컨테이너 폭에 맞춰지므로 별도 스케일 계산은 없다.
 * 좌석이 작아 터치가 어려울 때를 위해 +/- 확대(컨테이너 안 스크롤)를 제공한다.
 * 키보드: Tab으로 좌석 영역에 들어와 방향키로 이동, Enter/Space로 선택 (roving tabindex).
 */

const ZOOM_MIN = 1;
const ZOOM_MAX = 8;
const ZOOM_STEP = 1.5;

const cls = {
  toolbar: "flex flex-wrap items-center gap-2",
  zoomButton: `${button.outline} min-w-11 px-0`,
  zoomLabel: "min-w-12 text-center text-sm/[normal] text-gray-700",
  viewport:
    "max-h-[70dvh] overflow-auto overscroll-contain rounded-[10px] border border-gray-200 bg-white touch-pan-x touch-pan-y",
  svg: "block h-auto w-full select-none",
  rowLabel: "fill-gray-700 select-none",
  sectionLabel: "fill-gray-900 font-bold select-none",
} as const;

// 선택/비선택만 구분 (판매 상태 색 없음). 선은 확대해도 1px 유지.
// 포커스 시 선 두께·색도 바꿔 SVG outline 표시가 브라우저별로 달라도 보이게 한다 (FOCUS_RING과 병행)
const SEAT_BASE =
  `[vector-effect:non-scaling-stroke] cursor-pointer focus-visible:stroke-primary-600 focus-visible:stroke-[3px] ${FOCUS_RING} `;
const SEAT_IDLE = `${SEAT_BASE}fill-gray-200 stroke-gray-500`;
const SEAT_SELECTED = `${SEAT_BASE}fill-primary-600 stroke-primary-800`;

interface SeatRectProps {
  seat: SeatCoordinate;
  selected: boolean;
  tabbable: boolean;
  multiSection: boolean;
}

/** 좌석 하나 — props가 같으면 다시 그리지 않는다 (수천 개 대응). 이벤트는 <svg>에서 위임 처리 */
const SeatRect = memo(function SeatRect({ seat, selected, tabbable, multiSection }: SeatRectProps) {
  return (
    <rect
      data-uid={seat.uid}
      x={seat.x}
      y={seat.y}
      width={seat.w}
      height={seat.h}
      className={selected ? SEAT_SELECTED : SEAT_IDLE}
      role="button"
      tabIndex={tabbable ? 0 : -1}
      aria-pressed={selected}
      aria-label={seatLabel(seat, multiSection)}
    />
  );
});

function median(values: number[]): number {
  if (values.length === 0) return 10;
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.floor(sorted.length / 2)];
}

export default function SeatMapOverlay({ imageWidth, imageHeight, seats, selectedUid, onSelectSeat }: Props) {
  const svgRef = useRef<SVGSVGElement>(null);
  const [zoom, setZoom] = useState(1);
  /** 방향키 이동 기준(roving tabindex). 선택된 좌석이 있으면 그것, 없으면 첫 좌석 */
  const [focusUid, setFocusUid] = useState<string | null>(null);

  const layout = useMemo(() => {
    const byUid = new Map<string, SeatCoordinate>();
    const byRow = new Map<string, { section: number; row: number; seats: SeatCoordinate[] }>();
    for (const seat of seats) {
      byUid.set(seat.uid, seat);
      const section = seatSection(seat);
      const key = `${section}:${seat.row}`;
      const group = byRow.get(key);
      if (group) group.seats.push(seat);
      else byRow.set(key, { section, row: seat.row, seats: [seat] });
    }
    // 열 라벨·방향키 이동은 (구역, 열) 단위 — 같은 열 번호가 층마다 중복돼도 구분된다
    const rows = [...byRow.values()]
      .sort((a, b) => a.section - b.section || a.row - b.row)
      .map((g) => ({ ...g, seats: g.seats.sort((a, b) => a.x - b.x) }));

    const fontSize = Math.max(6, median(seats.map((s) => s.h)) * 0.8);
    const labels = rows.map(({ section, row, seats: list }) => {
      const first = list[0];
      const avgY = list.reduce((sum, s) => sum + s.y + s.h / 2, 0) / list.length;
      return { key: `${section}:${row}`, row, x: first.x - fontSize * 0.4, y: avgY };
    });
    // 구역이 둘 이상이면 각 구역 왼쪽 위에 "구역 N" 라벨 (좌표는 그대로)
    const multiSection = hasMultipleSections(seats);
    const bySection = new Map<number, { minX: number; minY: number }>();
    for (const seat of seats) {
      const sec = seatSection(seat);
      const b = bySection.get(sec);
      if (!b) bySection.set(sec, { minX: seat.x, minY: seat.y });
      else {
        if (seat.x < b.minX) b.minX = seat.x;
        if (seat.y < b.minY) b.minY = seat.y;
      }
    }
    const sectionLabels = multiSection
      ? [...bySection.entries()]
          .sort(([a], [b]) => a - b)
          .map(([section, b]) => ({ section, x: b.minX, y: b.minY - fontSize * 0.4 }))
      : [];
    // 열 번호 라벨이 viewBox 밖으로 잘리지 않게 왼쪽 여백을 확보
    const labelRoom = fontSize * 3.2;
    let minX = seats.length ? seats[0].x : 0;
    for (const seat of seats) if (seat.x < minX) minX = seat.x;
    const viewX = Math.min(0, minX - labelRoom);
    // 구역 라벨이 위쪽에서 잘리지 않게 여백 확보
    const minLabelY = sectionLabels.reduce((m, l) => Math.min(m, l.y), 0);
    const viewY = Math.min(0, minLabelY - fontSize * 1.2);
    return { byUid, rows, labels, sectionLabels, multiSection, fontSize, viewX, viewY };
  }, [seats]);

  const tabUid = focusUid && layout.byUid.has(focusUid) ? focusUid : selectedUid && layout.byUid.has(selectedUid) ? selectedUid : (seats[0]?.uid ?? null);

  const focusSeat = (uid: string) => {
    setFocusUid(uid);
    const el = svgRef.current?.querySelector<SVGElement>(`[data-uid="${CSS.escape(uid)}"]`);
    el?.focus();
  };

  const seatFromEvent = (target: EventTarget): SeatCoordinate | null => {
    if (!(target instanceof Element)) return null;
    const uid = target.closest("[data-uid]")?.getAttribute("data-uid");
    return uid ? (layout.byUid.get(uid) ?? null) : null;
  };

  const handleClick = (e: MouseEvent<SVGSVGElement>) => {
    const seat = seatFromEvent(e.target);
    if (!seat) return;
    setFocusUid(seat.uid);
    onSelectSeat(seat);
  };

  const handleFocus = (e: FocusEvent<SVGSVGElement>) => {
    const seat = seatFromEvent(e.target);
    if (seat) setFocusUid(seat.uid);
  };

  const handleKeyDown = (e: KeyboardEvent<SVGSVGElement>) => {
    const seat = seatFromEvent(e.target);
    if (!seat) return;
    if (e.key === "Enter" || e.key === " ") {
      e.preventDefault();
      onSelectSeat(seat);
      return;
    }
    const rowIndex = layout.rows.findIndex((r) => r.row === seat.row && r.section === seatSection(seat));
    if (rowIndex < 0) return;
    const rowSeats = layout.rows[rowIndex].seats;
    const idx = rowSeats.findIndex((s) => s.uid === seat.uid);

    let next: SeatCoordinate | undefined;
    if (e.key === "ArrowLeft") next = rowSeats[idx - 1];
    else if (e.key === "ArrowRight") next = rowSeats[idx + 1];
    else if (e.key === "ArrowUp" || e.key === "ArrowDown") {
      const target = layout.rows[rowIndex + (e.key === "ArrowUp" ? -1 : 1)];
      const cx = seat.x + seat.w / 2;
      next = target?.seats.reduce<SeatCoordinate | undefined>(
        (best, s) => (!best || Math.abs(s.x + s.w / 2 - cx) < Math.abs(best.x + best.w / 2 - cx) ? s : best),
        undefined
      );
    } else return;

    e.preventDefault();
    if (next) focusSeat(next.uid);
  };

  const changeZoom = useCallback((factor: number | "reset") => {
    setZoom((z) => (factor === "reset" ? 1 : Math.min(ZOOM_MAX, Math.max(ZOOM_MIN, z * factor))));
  }, []);

  const viewBox = `${layout.viewX} ${layout.viewY} ${imageWidth - layout.viewX} ${imageHeight - layout.viewY}`;

  return (
    <div className="flex flex-col gap-2">
      <div className={cls.toolbar} role="group" aria-label="좌석표 확대·축소">
        <button
          type="button"
          className={cls.zoomButton}
          onClick={() => changeZoom(1 / ZOOM_STEP)}
          disabled={zoom <= ZOOM_MIN}
          aria-label="축소"
        >
          −
        </button>
        <span className={cls.zoomLabel} aria-live="polite">
          {Math.round(zoom * 100)}%
        </span>
        <button
          type="button"
          className={cls.zoomButton}
          onClick={() => changeZoom(ZOOM_STEP)}
          disabled={zoom >= ZOOM_MAX}
          aria-label="확대"
        >
          +
        </button>
        <button type="button" className={button.outline} onClick={() => changeZoom("reset")} disabled={zoom === 1}>
          원래 크기
        </button>
        <span className={ui.hint}>좌석이 작으면 확대한 뒤 눌러 보세요.</span>
      </div>

      <div className={cls.viewport}>
        <div style={{ width: `${zoom * 100}%` }}>
          <svg
            ref={svgRef}
            className={cls.svg}
            viewBox={viewBox}
            role="group"
            aria-label={`좌석표, 좌석 ${seats.length}개. 방향키로 이동하고 Enter로 선택`}
            onClick={handleClick}
            onKeyDown={handleKeyDown}
            onFocus={handleFocus}
          >
            {layout.labels.map((l) => (
              <text
                key={l.key}
                x={l.x}
                y={l.y}
                fontSize={layout.fontSize}
                textAnchor="end"
                dominantBaseline="central"
                className={cls.rowLabel}
                aria-hidden="true"
                pointerEvents="none"
              >
                {l.row}
              </text>
            ))}
            {layout.sectionLabels.map((l) => (
              <text
                key={`section-${l.section}`}
                x={l.x}
                y={l.y}
                fontSize={layout.fontSize}
                textAnchor="start"
                dominantBaseline="text-after-edge"
                className={cls.sectionLabel}
                pointerEvents="none"
              >
                {`구역 ${l.section}`}
              </text>
            ))}
            {seats.map((seat) => (
              <SeatRect
                key={seat.uid}
                seat={seat}
                selected={seat.uid === selectedUid}
                tabbable={seat.uid === tabUid}
                multiSection={layout.multiSection}
              />
            ))}
          </svg>
        </div>
      </div>
    </div>
  );
}
