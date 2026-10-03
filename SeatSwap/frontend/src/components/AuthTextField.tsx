import type { InputHTMLAttributes } from "react";

// FR-01 로그인/회원가입 공용 입력칸: 라벨 + 입력 + (안내) + 필드 오류를 접근성 속성과 함께 묶는다.

// text-base(16px): iOS Safari 입력 시 자동 확대 방지. 높이 48px 터치 영역
// outline-hidden: 기본 외곽선은 숨기되 강제 색상 모드(Windows 고대비)에서는 유지된다
const INPUT_BASE =
  "min-h-12 w-full rounded-[10px] border bg-white px-3.5 text-base/[normal] text-gray-900 outline-hidden " +
  "placeholder:text-gray-500 focus-visible:ring-3 disabled:bg-gray-100";
// 오류 여부에 따라 테두리·포커스 링 색을 한쪽만 붙인다 (같은 속성 클래스를 동시에 넣어 순서에 의존하지 않음)
const INPUT_NORMAL = "border-gray-300 focus-visible:border-primary-600 focus-visible:ring-primary-600/20";
const INPUT_INVALID = "border-red-600 focus-visible:ring-red-600/20";

interface AuthTextFieldProps extends Omit<InputHTMLAttributes<HTMLInputElement>, "id" | "className"> {
  id: string;
  label: string;
  /** 필드 오류 메시지 — 있으면 입력칸 아래 표시하고 aria-invalid 처리 */
  error?: string;
  /** 입력 규칙 안내 (오류가 없을 때만 표시) */
  hint?: string;
}

export default function AuthTextField({ id, label, error, hint, ...inputProps }: AuthTextFieldProps) {
  const errorId = `${id}-error`;
  const hintId = `${id}-hint`;
  const showHint = !!hint && !error;
  const describedBy = error ? errorId : showHint ? hintId : undefined;

  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={id} className="text-sm/[normal] font-semibold text-gray-700">
        {label}
      </label>
      <input
        {...inputProps}
        id={id}
        className={`${INPUT_BASE} ${error ? INPUT_INVALID : INPUT_NORMAL}`}
        aria-invalid={!!error}
        aria-describedby={describedBy}
      />
      {showHint && (
        <p id={hintId} className="text-[0.8125rem]/[normal] text-gray-500">
          {hint}
        </p>
      )}
      {error && (
        <p id={errorId} className="text-[0.8125rem]/[normal] text-red-600">
          {error}
        </p>
      )}
    </div>
  );
}
