export interface ExchangeRequest {
  id: number;
  ticketId: number;
  desiredCondition: string;
  extraPayment: number | null;
  status: "OPEN" | "MATCHED" | "CLOSED";
}

export interface ExchangeMatch {
  id: number;
  requestAId: number;
  requestBId: number;
  status: "PROPOSED" | "ACCEPTED" | "COMPLETED" | "CANCELLED";
}
