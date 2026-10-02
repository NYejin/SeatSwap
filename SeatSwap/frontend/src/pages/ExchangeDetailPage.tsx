import { useParams } from "react-router-dom";

export default function ExchangeDetailPage() {
  const { id } = useParams();
  // TODO: 요청 상세, 신청/수락 버튼 (UC-05)
  return <div>ExchangeDetailPage {id}</div>;
}
