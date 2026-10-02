import { useParams } from "react-router-dom";
// import SeatMapOverlay from "../components/SeatMapOverlay";
// import SeatMapErrorReportButton from "../components/SeatMapErrorReportButton";

export default function SeatMapSelectPage() {
  const { id } = useParams();
  // TODO: seatMapApi.getSeatMap(id) 호출 → SeatMapOverlay 렌더링 (UC-03, UC-04)
  return <div>SeatMapSelectPage {id}</div>;
}
