import { useParams } from "react-router-dom";

export default function ChatPage() {
  const { matchId } = useParams();
  // TODO: WebSocket 연결, 메시지 목록/입력 (UC-06)
  return <div>ChatPage {matchId}</div>;
}
