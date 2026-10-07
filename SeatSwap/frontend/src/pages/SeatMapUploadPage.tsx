import { useEffect, useRef, useState, type ChangeEvent, type FormEvent } from "react";
import { Link, useNavigate, useParams, useSearchParams } from "react-router-dom"; // TEMP-DRAFT-DELETE: useSearchParams
import { parseSeatMapError, seatMapApi, type SeatMapErrorInfo } from "../api/seatmap";
import TextField from "../components/TextField";
import { button, FOCUS_RING, liveRegionClass, ui } from "../components/ui";
import type { AisleMode } from "../types/seatmap";

// UC-03 좌석표 등록 (보호 라우트 /venues/:venueId/seatmaps/new).
// 사용자가 캡처한 이미지를 올려 좌석 좌표를 인식한다. 이미지는 서버에 저장되지 않고 좌표만 저장된다.
// 미리보기는 브라우저 메모리(URL.createObjectURL)에서만 한다.

const MAX_BYTES = 10 * 1024 * 1024;
const ACCEPT = "image/png,image/jpeg,image/webp";
const ALLOWED_TYPES = ACCEPT.split(",");
const ZONE_MAX = 50;

const cls = {
  fileInput:
    "min-h-12 w-full cursor-pointer rounded-[10px] border border-gray-300 bg-white text-base/[normal] text-gray-900 " +
    "file:mr-3 file:min-h-12 file:cursor-pointer file:border-0 file:bg-primary-50 file:px-4 file:font-semibold file:text-primary-800 " +
    FOCUS_RING,
  preview: "max-h-64 w-full rounded-[10px] border border-gray-200 bg-gray-100 object-contain",
  summary: "flex min-h-11 cursor-pointer items-center text-sm/[normal] font-semibold text-gray-700 " + FOCUS_RING,
  radioRow: "flex min-h-11 items-start gap-2 text-[0.9375rem]/[normal] text-gray-900",
  spinner: "inline-block size-4 animate-spin rounded-full border-2 border-primary-600 border-t-transparent",
} as const;

function validateFile(file: File): string | null {
  if (!ALLOWED_TYPES.includes(file.type)) return "PNG, JPEG, WebP 이미지만 올릴 수 있어요.";
  if (file.size > MAX_BYTES) return "10MB 이하의 이미지를 올려주세요.";
  return null;
}

interface UploadError {
  message: string;
  /** 409: 이미 있는 임시 좌석표 */
  existingSeatMapId?: number;
  /** 구역 수 한도 초과: 공연 상세(이전 화면)로 돌아가는 링크 노출 */
  backToDetail?: boolean;
}

const MAYBE_SAVED = " 이미 등록됐을 수 있어요. 공연 상세의 좌석표 목록에서 확인해 주세요.";
const CANCELLED_MESSAGE =
  "요청을 취소했어요. 서버는 계속 처리 중일 수 있어 임시 좌석표가 저장됐을 수 있어요. 공연 상세의 좌석표 목록에서 확인해 주세요.";

/** 오류를 한국어 안내로 변환 */
function describeError(info: SeatMapErrorInfo): UploadError {
  if (info.timedOut) {
    return {
      message: "인식이 너무 오래 걸려 중단했어요. 이미지를 더 작게 줄이거나 잠시 후 다시 시도해 주세요." + MAYBE_SAVED,
    };
  }
  // 응답을 받지 못한 네트워크 오류 — 서버는 처리를 마쳤을 수 있다
  if (info.status === null) return { message: info.message + MAYBE_SAVED };
  switch (info.status) {
    case 409:
      // 기존 좌석표 id가 있을 때만 전용 안내, 없으면 서버 메시지
      if (info.seatMapId === null) return { message: info.message };
      return { message: "이 공연장에 이미 임시 좌석표가 있어요.", existingSeatMapId: info.seatMapId };
    case 429:
      // 같은 429라도 code로 구분: 하루 한도 / 짧은 시간 내 과다 요청(RATE_LIMITED)
      if (info.code === "DAILY_LIMIT_REACHED") {
        return { message: "하루에 등록할 수 있는 좌석표 수를 넘었어요. 내일 다시 시도해 주세요." };
      }
      return { message: info.message };
    case 413:
      return { message: "이미지 용량이 너무 커요. 10MB 이하의 이미지를 올려주세요." };
    case 415:
      return { message: "지원하지 않는 이미지 형식이에요. PNG, JPEG, WebP를 올려주세요." };
    case 422:
      if (info.code === "ZONE_LIMIT_REACHED") {
        return {
          message: "이 공연장에 등록할 수 있는 구역 수를 넘었어요. 기존 구역을 확인해 주세요.",
          backToDetail: true,
        };
      }
      if (info.code === "NO_SEATS_DETECTED") {
        return { message: "좌석을 찾지 못했어요. 좌석이 또렷한 캡처 이미지를 올려주세요." };
      }
      if (info.code === "IMAGE_TOO_COMPLEX") {
        return { message: "이미지가 너무 복잡해 인식하지 못했어요. 좌석 부분만 잘라서 다시 올려주세요." };
      }
      return { message: info.message };
    case 502:
    case 503:
    case 504:
      return { message: "좌석 인식 서비스가 잠시 응답하지 않아요. 잠시 후 다시 시도해 주세요." + MAYBE_SAVED };
    default:
      return { message: info.message };
  }
}

export default function SeatMapUploadPage() {
  const { venueId: venueIdParam } = useParams();
  const venueId = Number(venueIdParam);
  const validVenue = Number.isInteger(venueId) && venueId > 0;
  const navigate = useNavigate();
  // TODO: 임시 기능
  const [searchParams] = useSearchParams(); // TEMP-DRAFT-DELETE: ?zone= 로 구역명 미리 채움

  const [file, setFile] = useState<File | null>(null);
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [fileError, setFileError] = useState<string>();
  const [zoneName, setZoneName] = useState(() => (searchParams.get("zone") ?? "").slice(0, ZONE_MAX)); // TEMP-DRAFT-DELETE: 원래 useState("")
  const [aisleMode, setAisleMode] = useState<AisleMode>("continue");
  const [uploading, setUploading] = useState(false);
  const [elapsed, setElapsed] = useState(0);
  const [error, setError] = useState<UploadError | null>(null);
  const abortRef = useRef<AbortController | null>(null);

  // 미리보기 URL은 파일이 바뀌거나 화면을 떠날 때 해제 (브라우저 메모리에서만 사용)
  useEffect(() => {
    if (!file) {
      setPreviewUrl(null);
      return;
    }
    const url = URL.createObjectURL(file);
    setPreviewUrl(url);
    return () => URL.revokeObjectURL(url);
  }, [file]);

  // 업로드 중 경과 시간 표시
  useEffect(() => {
    if (!uploading) return;
    setElapsed(0);
    const timer = window.setInterval(() => setElapsed((s) => s + 1), 1000);
    return () => window.clearInterval(timer);
  }, [uploading]);

  // 화면을 떠나면 진행 중 요청 취소
  useEffect(() => () => abortRef.current?.abort(), []);

  const onFileChange = (e: ChangeEvent<HTMLInputElement>) => {
    const picked = e.target.files?.[0] ?? null;
    setError(null);
    if (!picked) {
      setFile(null);
      setFileError(undefined);
      return;
    }
    const problem = validateFile(picked);
    setFileError(problem ?? undefined);
    setFile(problem ? null : picked);
    if (problem) e.target.value = "";
  };

  const cancelUpload = () => {
    abortRef.current?.abort();
    abortRef.current = null;
    setUploading(false);
    setError({ message: CANCELLED_MESSAGE });
  };

  const submit = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (uploading) return;
    if (!file) {
      setFileError("좌석표 이미지를 선택해 주세요.");
      return;
    }
    const problem = validateFile(file);
    if (problem) {
      setFileError(problem);
      return;
    }
    const controller = new AbortController();
    abortRef.current = controller;
    setUploading(true);
    setError(null);
    try {
      const seatMap = await seatMapApi.upload(venueId, { file, zoneName, aisleMode }, controller.signal);
      navigate(`/seatmaps/${seatMap.id}/select`);
    } catch (err) {
      if (controller.signal.aborted) return;
      setError(describeError(parseSeatMapError(err, "좌석표를 등록하지 못했습니다.")));
      setUploading(false);
    }
  };

  if (!validVenue) {
    return (
      <div className={ui.page}>
        <div className={ui.container}>
          <div className={`${ui.card} flex flex-col items-start gap-3`}>
            <h1 className={ui.pageTitle}>공연장을 찾을 수 없어요</h1>
            <p className={ui.body}>잘못된 주소예요.</p>
            <Link to="/" className={button.outline}>
              공연 목록으로
            </Link>
          </div>
        </div>
      </div>
    );
  }

  // 라이브 영역에는 시작 시 고정 문구만 둔다 (경과 초는 매초 낭독되지 않게 aria-hidden으로 분리)
  const statusText = uploading ? "이미지를 분석하고 있어요. 최대 1분 넘게 걸릴 수 있어요." : "";

  return (
    <div className={ui.page}>
      <div className={ui.container}>
        <form className={`${ui.card} flex flex-col gap-4`} onSubmit={submit} noValidate aria-busy={uploading}>
          <h1 className={ui.pageTitle}>좌석표 등록</h1>
          <p className={ui.body}>
            예매 화면 등에서 캡처한 좌석표 이미지를 올리면 좌석 위치를 자동으로 인식해요.
          </p>
          <p className={ui.notice}>이미지는 서버에 저장되지 않아요. 인식한 좌석 좌표만 저장돼요.</p>

          <div className="flex flex-col gap-1.5">
            <label htmlFor="seatmap-file" className={ui.label}>
              좌석표 이미지
            </label>
            <input
              id="seatmap-file"
              type="file"
              accept={ACCEPT}
              className={cls.fileInput}
              onChange={onFileChange}
              disabled={uploading}
              aria-invalid={!!fileError}
              aria-describedby={fileError ? "seatmap-file-error" : "seatmap-file-hint"}
            />
            {fileError ? (
              <p id="seatmap-file-error" className={ui.fieldError} role="alert">
                {fileError}
              </p>
            ) : (
              <p id="seatmap-file-hint" className={ui.hint}>
                PNG, JPEG, WebP · 10MB 이하
              </p>
            )}
          </div>

          {previewUrl && (
            <img src={previewUrl} alt="선택한 좌석표 이미지 미리보기" className={cls.preview} />
          )}

          <TextField
            id="seatmap-zone"
            label="구역 이름 (선택)"
            value={zoneName}
            onChange={(e) => setZoneName(e.target.value)}
            maxLength={ZONE_MAX}
            disabled={uploading}
            placeholder="예: 1층, A구역"
            hint="구역이 나뉜 공연장이면 입력해 주세요."
          />

          <details className="rounded-[10px] border border-gray-200 px-3.5">
            <summary className={cls.summary}>고급</summary>
            <fieldset className="flex flex-col gap-1 pb-3" disabled={uploading}>
              <legend className={`${ui.label} mb-1`}>번호 통로 처리</legend>
              <label className={cls.radioRow}>
                <input
                  type="radio"
                  name="aisleMode"
                  className="mt-3 size-4 accent-primary-600"
                  checked={aisleMode === "continue"}
                  onChange={() => setAisleMode("continue")}
                />
                <span className="py-2.5">통로를 건너도 번호를 이어서 (기본)</span>
              </label>
              <label className={cls.radioRow}>
                <input
                  type="radio"
                  name="aisleMode"
                  className="mt-3 size-4 accent-primary-600"
                  checked={aisleMode === "skip"}
                  onChange={() => setAisleMode("skip")}
                />
                <span className="py-2.5">통로 자리를 결번으로</span>
              </label>
              <p className={ui.hint}>실제 좌석 번호와 다르면 나중에 수정을 요청할 수 있어요.</p>
            </fieldset>
          </details>

          <p className={liveRegionClass(statusText, `${ui.status} flex items-center gap-2`)} role="status">
            {uploading && <span className={cls.spinner} aria-hidden="true" />}
            {statusText}
            {uploading && <span aria-hidden="true">({elapsed}초)</span>}
          </p>
          {uploading && (
            <p className={ui.hint}>
              지금 취소해도 서버는 계속 처리해 임시 좌석표가 저장될 수 있어요.
            </p>
          )}

          {error && (
            <div className={`${ui.errorBox} flex flex-col gap-2`} role="alert">
              <span>{error.message}</span>
              {error.existingSeatMapId !== undefined && (
                <Link to={`/seatmaps/${error.existingSeatMapId}/select`} className={ui.link}>
                  임시 좌석표 보기
                </Link>
              )}
              {error.backToDetail && (
                <button type="button" className={`${ui.link} self-start`} onClick={() => navigate(-1)}>
                  공연 상세로 돌아가기
                </button>
              )}
            </div>
          )}

          <div className="flex flex-wrap justify-end gap-2">
            <button type="button" className={button.outline} onClick={uploading ? cancelUpload : () => navigate(-1)}>
              {uploading ? "요청 취소" : "취소"}
            </button>
            <button type="submit" className={button.solid} disabled={uploading} aria-busy={uploading}>
              {uploading ? "인식 중..." : "좌석 인식하기"}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
