import { useParams } from "react-router-dom";

export default function PerformanceDetailPage() {
  const { id } = useParams();
  // TODO: 공연 정보 + 해당 공연 교환 요청 목록 (UC-02 연계)
  return <div>PerformanceDetailPage {id}</div>;
}
