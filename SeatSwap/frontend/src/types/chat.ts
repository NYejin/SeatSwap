export interface ChatMessage {
  id: number;
  chatRoomId: number;
  senderId: number;
  content: string;
  sentAt: string;
}
