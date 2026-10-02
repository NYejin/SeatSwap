import type { InputHTMLAttributes } from "react";
import styles from "./AuthForm.module.css";

// FR-01 로그인/회원가입 공용 입력칸: 라벨 + 입력 + (안내) + 필드 오류를 접근성 속성과 함께 묶는다.

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
    <div className={styles.field}>
      <label htmlFor={id} className={styles.label}>
        {label}
      </label>
      <input
        {...inputProps}
        id={id}
        className={styles.input}
        aria-invalid={!!error}
        aria-describedby={describedBy}
      />
      {showHint && (
        <p id={hintId} className={styles.hint}>
          {hint}
        </p>
      )}
      {error && (
        <p id={errorId} className={styles.fieldError}>
          {error}
        </p>
      )}
    </div>
  );
}
