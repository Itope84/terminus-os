// Ids the server accepts: chats `s-<time>` (isChatId), turns [A-Za-z0-9_-]{1,64}.
export const newChatId = (): string => `s-${Date.now()}`;
export const newTurnId = (): string => `t-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
