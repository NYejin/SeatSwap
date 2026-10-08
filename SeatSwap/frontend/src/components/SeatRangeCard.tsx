import type { RangeErrors, RangeField, RangeInput } from "../api/seat";
import TextField from "./TextField";
import { button } from "./ui";

// 희망 좌석 범위 카드 1장: (구역, 열 시작~끝, 번 시작~끝). 좌석표 없이 텍스트로 입력한다.
// 회차·추가금은 범위가 아니라 요청 단위라 여기에 없다. 반드시 모듈 최상위 컴포넌트로 쓴다.

interface SeatRangeCardProps {
  /** 카드 고유 번호 (입력 id에 쓰인다 — 배열 순서가 바뀌어도 포커스·입력이 유지된다) */
  uid: number;
  /** 화면에 보이는 순번 (1부터) */
  number: number;
  value: RangeInput;
  errors: RangeErrors;
  disabled?: boolean;
  onChange: (field: RangeField, value: string) => void;
  onRemove: () => void;
  /** 카드가 1장뿐일 때는 지울 수 없다 */
  removable: boolean;
}

/** 입력 요소 id (오류 포커스 이동용) */
export const rangeFieldId = (uid: number, field: RangeField) => `range-${uid}-${field}`;

export default function SeatRangeCard({
  uid,
  number,
  value,
  errors,
  disabled = false,
  onChange,
  onRemove,
  removable,
}: SeatRangeCardProps) {
  const field = (name: RangeField, label: string, extra?: { hint?: string; placeholder?: string }) => (
    <TextField
      id={rangeFieldId(uid, name)}
      label={label}
      value={value[name]}
      onChange={(e) => onChange(name, e.target.value)}
      error={errors[name]}
      hint={extra?.hint}
      placeholder={extra?.placeholder}
      disabled={disabled}
      autoComplete="off"
      autoCapitalize="characters"
      spellCheck={false}
    />
  );

  return (
    <fieldset className="flex min-w-0 flex-col gap-3 rounded-[10px] border border-gray-200 p-3.5" disabled={disabled}>
      <legend className="px-1 text-sm/[normal] font-bold text-gray-900">희망 범위 {number}</legend>
      {field("zone", "구역", { hint: "티켓에 적힌 그대로 입력해주세요. (예: 1층 A구역)", placeholder: "예: 1층 A" })}
      <div className="grid grid-cols-2 gap-3">
        {field("rowFrom", "열 시작", { placeholder: "예: 3" })}
        {field("rowTo", "열 끝", { placeholder: "비우면 한 열" })}
        {field("colFrom", "번 시작", { placeholder: "예: 3" })}
        {field("colTo", "번 끝", { placeholder: "비우면 한 자리" })}
      </div>
      {removable && (
        <div>
          <button type="button" className={button.dangerOutline} onClick={onRemove} disabled={disabled}>
            희망 범위 {number} 삭제
          </button>
        </div>
      )}
    </fieldset>
  );
}
