/** 백엔드 페이지 응답 공통 포맷. page는 0부터 */
export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}
