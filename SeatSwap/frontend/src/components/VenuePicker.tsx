import { useEffect, useState, type FormEvent } from "react";
import { venuesApi } from "../api/venues";
import { parseApiError } from "../api/errors";
import { useDebouncedValue } from "../hooks/useDebouncedValue";
import type { Venue } from "../types/performance";
import TextField from "./TextField";
import { FOCUS_RING, button, input, liveRegionClass, ui } from "./ui";

// 공연장 검색·선택 + 없으면 새로 추가 (FR-02). 공연 등록 ③단계, 공연 상세의 공연장 변경에서 공용.

const cls = {
  results: "flex flex-col gap-2",
  /** 검색 결과 한 줄 — 선택 여부에 따라 완성된 문자열 중 하나 */
  option:
    "flex w-full min-h-11 cursor-pointer touch-manipulation flex-col items-start gap-0.5 rounded-[10px] border px-3.5 py-2.5 " +
    "text-left " +
    FOCUS_RING,
  optionIdle: "border-gray-300 bg-white active:bg-gray-100",
  optionSelected: "border-primary-600 bg-primary-50",
  optionName: "text-[0.9375rem]/[normal] font-semibold text-gray-900",
  /** gray-700 / 흰·primary-50 배경 모두 AA 통과 */
  optionAddress: "text-sm/[normal] text-gray-700",
  selectedBox: "flex flex-col gap-0.5 rounded-[10px] border border-primary-600 bg-primary-50 px-3.5 py-3",
  selectedLabel: "text-[0.8125rem]/[normal] font-semibold text-primary-800",
  addForm: "flex flex-col gap-3 rounded-[10px] border border-gray-200 p-4",
} as const;

const optionClass = (selected: boolean) => `${cls.option} ${selected ? cls.optionSelected : cls.optionIdle}`;

type SearchState =
  | { status: "idle" }
  | { status: "loading" }
  | { status: "success"; venues: Venue[] }
  | { status: "error"; message: string };

interface VenuePickerProps {
  /** 요소 id 접두사 (한 화면에 여러 개 있어도 겹치지 않게) */
  idPrefix: string;
  selected: Venue | null;
  onSelect: (venue: Venue) => void;
  /** 상위 폼 검증 오류 (예: "공연장을 선택해주세요") */
  error?: string;
}

export default function VenuePicker({ idPrefix, selected, onSelect, error }: VenuePickerProps) {
  const [query, setQuery] = useState("");
  const debouncedQuery = useDebouncedValue(query.trim(), 300);
  const [search, setSearch] = useState<SearchState>({ status: "idle" });
  const [retryKey, setRetryKey] = useState(0);

  const [adding, setAdding] = useState(false);
  const [newName, setNewName] = useState("");
  const [newAddress, setNewAddress] = useState("");
  const [addErrors, setAddErrors] = useState<{ name?: string; address?: string }>({});
  const [addError, setAddError] = useState<string | null>(null);
  const [addSubmitting, setAddSubmitting] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);

  // 디바운스된 검색어로 조회. 이전 요청은 취소해 늦게 온 결과가 덮어쓰지 않게 한다
  useEffect(() => {
    if (!debouncedQuery) {
      setSearch({ status: "idle" });
      return;
    }
    const controller = new AbortController();
    setSearch({ status: "loading" });
    venuesApi
      .search(debouncedQuery, controller.signal)
      .then((venues) => setSearch({ status: "success", venues }))
      .catch((err: unknown) => {
        if (controller.signal.aborted) return;
        setSearch({ status: "error", message: parseApiError(err, "공연장을 검색하지 못했습니다.").message });
      });
    return () => controller.abort();
  }, [debouncedQuery, retryKey]);

  const handleSelect = (venue: Venue) => {
    setNotice(null);
    onSelect(venue);
  };

  const openAddForm = () => {
    setAdding(true);
    setNewName(query.trim());
    setAddErrors({});
    setAddError(null);
  };

  const handleAdd = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (addSubmitting) return;
    const name = newName.trim();
    const address = newAddress.trim();
    const errors: { name?: string; address?: string } = {};
    if (!name) errors.name = "공연장 이름을 입력해주세요.";
    else if (name.length > 100) errors.name = "공연장 이름은 100자 이하로 입력해주세요.";
    if (address.length > 255) errors.address = "주소는 255자 이하로 입력해주세요.";
    setAddErrors(errors);
    setAddError(null);
    if (errors.name || errors.address) return;

    setAddSubmitting(true);
    try {
      const { venue, created } = await venuesApi.create({ name, address: address || undefined });
      onSelect(venue);
      setNotice(created ? "새 공연장을 추가하고 선택했어요." : "같은 공연장이 이미 있어 그 공연장을 선택했어요.");
      setAdding(false);
      setNewName("");
      setNewAddress("");
    } catch (err) {
      const parsed = parseApiError(err, "공연장을 추가하지 못했습니다.");
      const next = { name: parsed.fieldErrors.name, address: parsed.fieldErrors.address };
      setAddErrors(next);
      setAddError(next.name || next.address ? null : parsed.message);
    } finally {
      setAddSubmitting(false);
    }
  };

  const searchId = `${idPrefix}-venue-search`;
  const statusText =
    search.status === "loading"
      ? "검색 중..."
      : search.status === "success"
        ? search.venues.length > 0
          ? `검색 결과 ${search.venues.length}개`
          : "검색 결과가 없어요."
        : "";

  return (
    <div className="flex flex-col gap-3">
      {selected && (
        <div className={cls.selectedBox}>
          <span className={cls.selectedLabel}>선택한 공연장</span>
          <span className={cls.optionName}>{selected.name}</span>
          {selected.address && <span className={cls.optionAddress}>{selected.address}</span>}
        </div>
      )}
      <p className={liveRegionClass(notice, ui.notice)} role="status">
        {notice ?? ""}
      </p>

      <div className="flex flex-col gap-1.5">
        <label htmlFor={searchId} className={ui.label}>
          공연장 검색
        </label>
        <input
          id={searchId}
          type="search"
          className={error ? input.invalid : input.normal}
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="예: 올림픽공원 KSPO DOME"
          autoComplete="off"
          aria-invalid={!!error}
          aria-describedby={error ? `${searchId}-error` : undefined}
        />
        {error && (
          <p id={`${searchId}-error`} className={ui.fieldError}>
            {error}
          </p>
        )}
      </div>

      <p className={liveRegionClass(statusText, ui.status)} role="status">
        {statusText}
      </p>

      {search.status === "error" && (
        <div className="flex flex-col gap-2">
          <p className={ui.errorBox} role="alert">
            {search.message}
          </p>
          <button type="button" className={button.outline} onClick={() => setRetryKey((k) => k + 1)}>
            다시 검색
          </button>
        </div>
      )}

      {search.status === "success" && search.venues.length > 0 && (
        <ul className={cls.results} aria-label="공연장 검색 결과">
          {search.venues.map((venue) => {
            const isSelected = selected?.id === venue.id;
            return (
              <li key={venue.id}>
                <button
                  type="button"
                  className={optionClass(isSelected)}
                  aria-pressed={isSelected}
                  onClick={() => handleSelect(venue)}
                >
                  <span className={cls.optionName}>{venue.name}</span>
                  {venue.address && <span className={cls.optionAddress}>{venue.address}</span>}
                </button>
              </li>
            );
          })}
        </ul>
      )}

      {!adding ? (
        <button type="button" className={button.outline} onClick={openAddForm}>
          찾는 공연장이 없나요? 새 공연장 추가
        </button>
      ) : (
        <form className={cls.addForm} onSubmit={handleAdd} noValidate aria-label="새 공연장 추가">
          <TextField
            id={`${idPrefix}-venue-name`}
            label="공연장 이름"
            value={newName}
            onChange={(e) => setNewName(e.target.value)}
            disabled={addSubmitting}
            error={addErrors.name}
            maxLength={100}
          />
          <TextField
            id={`${idPrefix}-venue-address`}
            label="주소 (선택)"
            value={newAddress}
            onChange={(e) => setNewAddress(e.target.value)}
            disabled={addSubmitting}
            error={addErrors.address}
            maxLength={255}
          />
          {addError && (
            <p className={ui.errorBox} role="alert">
              {addError}
            </p>
          )}
          <div className="flex flex-wrap justify-end gap-2">
            <button type="button" className={button.outline} onClick={() => setAdding(false)} disabled={addSubmitting}>
              취소
            </button>
            <button type="submit" className={button.solid} disabled={addSubmitting} aria-busy={addSubmitting}>
              {addSubmitting ? "추가 중..." : "추가하고 선택"}
            </button>
          </div>
        </form>
      )}
    </div>
  );
}
