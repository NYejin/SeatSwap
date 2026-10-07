import type { SeatCoordinate } from "../types/seatmap";
import { button, ui } from "./ui";
import { seatLabel } from "./seatLabel";

interface Props {
  seat: SeatCoordinate;
  /** 좌석표에 구역이 둘 이상이면 true */
  multiSection?: boolean;
}

/**
 * 좌석 선택 시 항상 함께 노출되는 오류 신고 버튼 (FR-07).
 * 신고 API가 아직 없어 비활성 '준비 중'으로만 보여준다. API가 생기면 onSubmit(정정 내용)을 받아 연결한다.
 */
export default function SeatMapErrorReportButton({ seat, multiSection = false }: Props) {
  const describedBy = `seat-report-hint-${seat.uid}`;
  return (
    <div className="flex flex-col gap-1.5">
      <button
        type="button"
        className={button.outline}
        disabled
        aria-describedby={describedBy}
        aria-label={`${seatLabel(seat, multiSection)} 오류 신고 (준비 중)`}
      >
        오류 신고
      </button>
      <p id={describedBy} className={ui.hint}>
        오류 신고 기능은 준비 중이에요.
      </p>
    </div>
  );
}
