// One chat: header (title, backend · model, context ring, menu), the thread, the composer, and the
// sheets for model, goal, context and the chat menu.
import { useLocalSearchParams, useRouter } from 'expo-router';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Composer, type ComposerHandle } from '../../components/Composer';
import { ContextRing } from '../../components/ContextRing';
import { Screen } from '../../components/Screen';
import { Opt, Sheet } from '../../components/Sheet';
import { Thread } from '../../components/Thread';
import { call } from '../../lib/api';
import { newChatId } from '../../lib/ids';
import { deleteChat, markRead, toast, updateChat, useApp } from '../../lib/store';
import {
  addNote,
  clearLocal,
  connectThread,
  discardQueued,
  forgetThread,
  loadThread,
  reconcile,
  retry,
  send,
  sendNow,
  stop,
  takeBack,
  useThreads,
} from '../../lib/thread';
import type { Chat, Command, ContextInfo, Goal, Model } from '../../lib/types';
import { SUPPORTED_BACKENDS } from '../../lib/types';

const commandCache: Record<string, Command[]> = {};
const modelCache: Record<string, Model[]> = {};

export default function ChatScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const chatId = String(id);
  const router = useRouter();
  const chat = useApp((s) => s.chats[chatId]) as Chat | undefined;
  const online = useApp((s) => s.online);
  const backends = useApp((s) => s.backends);
  const thread = useThreads((s) => s.threads[chatId]);
  const msgs = thread?.msgs ?? [];
  const running = msgs.some((m) => m.status === 'live');
  const composer = useRef<ComposerHandle | null>(null);
  const linkRef = useRef<{ close: () => void; kick: () => void } | null>(null);
  const [sheet, setSheet] = useState<'' | 'model' | 'menu' | 'goal' | 'context' | 'rename'>('');
  const [ctx, setCtx] = useState<ContextInfo | null>(null);
  const [goal, setGoal] = useState<Goal | null>(null);
  const [commands, setCommands] = useState<Command[]>(commandCache[chat?.backend ?? ''] ?? []);
  const [models, setModels] = useState<Model[]>([]);
  const [renameTo, setRenameTo] = useState('');
  const [goalText, setGoalText] = useState('');
  const [goalLoop, setGoalLoop] = useState(true);
  const isInbox = chat?.kind === 'inbox';

  // Open: load, stream, mark read; presence says which chat is on screen.
  useEffect(() => {
    if (!chat) return;
    useApp.getState().set({ openChat: chatId });
    void loadThread(chat);
    void markRead(chatId);
    linkRef.current = connectThread(chatId);
    const onVis = () => {
      if (document.visibilityState === 'visible') {
        linkRef.current?.kick();
        void markRead(chatId);
      }
    };
    document.addEventListener('visibilitychange', onVis);
    return () => {
      document.removeEventListener('visibilitychange', onVis);
      linkRef.current?.close();
      linkRef.current = null;
      if (useApp.getState().openChat === chatId) useApp.getState().set({ openChat: '' });
    };
    // A new session id doesn't need a reconnect: the stream follows the chat id.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [chatId, !!chat]);

  const refreshContext = useCallback(async () => {
    if (!chat || isInbox) return;
    try {
      const q = new URLSearchParams({ chatId, backend: chat.backend });
      if (chat.sessionID) q.set('sessionID', chat.sessionID);
      setCtx(await call<ContextInfo>('GET', `/api/context?${q}`));
    } catch {
      /* ring stays empty */
    }
  }, [chat, chatId, isInbox]);

  useEffect(() => {
    if (!running) void refreshContext();
  }, [running, refreshContext]);

  useEffect(() => {
    if (!chat || isInbox) return;
    const b = chat.backend;
    if (commandCache[b]) return setCommands(commandCache[b]);
    call<{ commands: Command[] }>('GET', `/api/commands?backend=${encodeURIComponent(b)}`)
      .then((r) => {
        commandCache[b] = r.commands.filter((c) => c.available !== false);
        setCommands(commandCache[b]);
      })
      .catch(() => {});
  }, [chat?.backend, isInbox]);

  const loadModels = useCallback(async (backend: string) => {
    if (modelCache[backend]?.length) return setModels(modelCache[backend]);
    setModels([]);
    try {
      const r = await call<{ models: Model[] }>('GET', `/api/models?backend=${encodeURIComponent(backend)}`, undefined, { timeoutMs: 40_000 });
      modelCache[backend] = r.models;
      setModels(r.models);
    } catch (e) {
      toast(`Couldn’t list models: ${(e as Error).message}`);
    }
  }, []);

  const loadGoal = useCallback(async () => {
    try {
      const r = await call<{ goal: Goal | null }>('GET', `/api/goals?chatId=${encodeURIComponent(chatId)}`);
      setGoal(r.goal);
    } catch {
      setGoal(null);
    }
  }, [chatId]);
  useEffect(() => {
    if (!isInbox) void loadGoal();
  }, [loadGoal, isInbox, running]);

  const hasSent = msgs.some((m) => m.role === 'user') || !!chat?.sessionID;

  const actions = useMemo(
    () => ({
      onRetry: (msgId: string) => void retry(chatId, msgId),
      onEdit: (msgId: string) => composer.current?.setText(takeBack(chatId, msgId)),
      onSendNow: (turnId: string) => void sendNow(chatId, turnId),
      onDiscard: (turnId: string) => void discardQueued(chatId, turnId),
      onQuote: (text: string) => composer.current?.addQuote(text),
    }),
    [chatId],
  );

  const exportChat = () => {
    const lines = msgs.map((m) => `[${new Date(m.at).toLocaleString()}] ${m.role === 'user' ? 'You' : m.role === 'assistant' ? 'Agent' : m.role}:\n${m.title ? `${m.title}\n` : ''}${m.text}\n`);
    const blob = new Blob([`${chat?.title ?? 'Chat'}\n\n${lines.join('\n')}`], { type: 'text/plain' });
    const file = new File([blob], `${(chat?.title ?? 'chat').replace(/[^\w -]+/g, '').slice(0, 40) || 'chat'}.txt`, { type: 'text/plain' });
    const nav = navigator as Navigator & { canShare?: (d: unknown) => boolean };
    if (nav.share && nav.canShare?.({ files: [file] })) void nav.share({ files: [file], title: chat?.title }).catch(() => {});
    else {
      const a = document.createElement('a');
      a.href = URL.createObjectURL(blob);
      a.download = file.name;
      a.click();
    }
  };

  const compact = async () => {
    if (!chat) return;
    addNote(chatId, 'Compacting…');
    try {
      const r = await call<{ message?: string }>('POST', '/api/compact', { chatId, sessionID: chat.sessionID, backend: chat.backend }, { timeoutMs: 180_000 });
      addNote(chatId, r.message ? `${r.message[0].toUpperCase()}${r.message.slice(1)}.` : 'Compacted.');
      void refreshContext();
    } catch (e) {
      addNote(chatId, `Couldn’t compact: ${(e as Error).message}`);
    }
  };

  const armGoal = async (objective: string, loop: boolean) => {
    try {
      const r = await call<{ goal: Goal }>('POST', '/api/goals/arm', { chatId, objective, loop });
      setGoal(r.goal);
      setSheet('');
      toast(loop ? 'Goal set: it keeps going until the judge says it’s done.' : 'Goal set.');
    } catch (e) {
      toast((e as Error).message);
    }
  };
  const goalControl = async (what: 'pause' | 'resume' | 'remove') => {
    try {
      await call('POST', `/api/goals/${what}`, { chatId });
      await loadGoal();
    } catch (e) {
      toast((e as Error).message);
    }
  };

  const newChat = async () => {
    const id = newChatId();
    await updateChat(id, { title: 'New chat', backend: chat?.backend ?? 'claude', model: chat?.model, kind: 'chat', touch: true });
    router.replace(`/chat/${id}`);
  };

  const onCommand = (name: string, arg: string): boolean => {
    switch (name) {
      case 'new':
        void newChat();
        return true;
      case 'stop':
        void stop(chatId);
        return true;
      case 'model':
        if (arg) {
          void updateChat(chatId, { model: arg });
          toast(`Model: ${arg}`);
        } else {
          void loadModels(chat?.backend ?? 'claude');
          setSheet('model');
        }
        return true;
      case 'goal':
        if (arg) void armGoal(arg, true);
        else {
          setGoalText(goal?.objective ?? '');
          setSheet('goal');
        }
        return true;
      case 'context':
      case 'usage':
      case 'cost':
        void refreshContext();
        setSheet('context');
        return true;
      case 'compact':
        void compact();
        return true;
      case 'rename':
        if (arg) void updateChat(chatId, { title: arg.slice(0, 120) });
        else {
          setRenameTo(chat?.title ?? '');
          setSheet('rename');
        }
        return true;
      case 'fetch':
        if (!arg) {
          toast('Usage: /fetch <url>');
          return true;
        }
        void send(chatId, `Fetch ${arg} and tell me what it says. Use the web fetch routing in AGENTS.md: headless first, then Chrome.`);
        return true;
      case 'clear':
        clearLocal(chatId);
        return true;
      case 'export':
        exportChat();
        return true;
      case 'delete':
        if (chat && confirm('Delete this chat? Its session on the Mac goes too.')) {
          void deleteChat(chat);
          forgetThread(chatId);
          router.back();
        }
        return true;
      default:
        return false; // a backend command or skill: sent as text
    }
  };

  if (!chat) {
    return (
      <Screen>
        <div className="bar">
          <button type="button" className="icon" onClick={() => router.back()}>‹</button>
          <h1>Chat</h1>
        </div>
        <div className="empty">This chat isn’t on the Mac any more.</div>
      </Screen>
    );
  }

  const backendLabel = backends.find((b) => b.id === chat.backend)?.shortLabel ?? (chat.backend === 'inbox' ? 'Deliveries' : chat.backend);
  const sub = isInbox ? 'Deliveries from jobs and agents' : `${backendLabel}${chat.model ? ` · ${chat.model}` : ''}${goal && goal.status === 'active' ? ' · goal on' : ''}`;

  return (
    <Screen>
      <div className="bar">
        <button type="button" className="icon" aria-label="Back" onClick={() => (router.canGoBack() ? router.back() : router.replace('/'))}>‹</button>
        <div
          className="title"
          onClick={() => {
            if (isInbox) return;
            void loadModels(chat.backend);
            setSheet('model');
          }}
        >
          <b>{chat.title}</b>
          <span>
            <span className={`dot ${online && thread?.connected ? 'ok' : ''}`} style={{ marginRight: 6, verticalAlign: 'middle' }} />
            {sub}
          </span>
        </div>
        {!isInbox && ctx ? <ContextRing used={ctx.used} window={ctx.window} compactAt={ctx.compactAt} onClick={() => setSheet('context')} /> : null}
        <button type="button" className="icon" aria-label="Menu" onClick={() => setSheet('menu')}>⋯</button>
      </div>
      {thread?.loadError && !msgs.length ? <div className="banner warn">Couldn’t load the history: {thread.loadError}</div> : null}
      <Thread
        msgs={msgs}
        status={thread?.status ?? ''}
        actions={actions}
        empty={isInbox ? 'Nothing delivered here yet.' : thread?.loading ? 'Loading…' : `Ask ${backendLabel} anything. It runs on your Mac, with your skills and notebook.`}
        style={{}}
      />
      {isInbox ? null : (
        <Composer
          running={running}
          commands={commands}
          onSend={(text, files) => void send(chatId, text, { files })}
          onStop={() => void stop(chatId)}
          onCommand={onCommand}
          draftKey={`terminus.draft.${chatId}`}
          bindHandle={(h) => {
            composer.current = h;
          }}
        />
      )}

      <Sheet open={sheet === 'model'} onClose={() => setSheet('')}>
        <h2>Backend and model</h2>
        {hasSent ? <p>The backend is fixed once a chat has started. Start a new chat to switch.</p> : null}
        {SUPPORTED_BACKENDS.map((id) => {
          const b = backends.find((x) => x.id === id);
          const ready = b?.available && b.authenticated;
          return (
            <Opt
              key={id}
              title={b?.label ?? id}
              sub={ready ? (id === 'claude' ? 'Claude Code on the Mac · can use Claude in Chrome' : 'OpenCode on the Mac') : 'Not installed or not signed in on the Mac'}
              disabled={hasSent || !ready}
              right={chat.backend === id ? '✓' : null}
              onClick={() => {
                void updateChat(chatId, { backend: id, model: null as unknown as string });
                void loadModels(id);
              }}
            />
          );
        })}
        <h2 style={{ marginTop: 18 }}>Model</h2>
        <Opt title="Default" sub="Whatever the backend uses" right={!chat.model ? '✓' : null} onClick={() => void updateChat(chatId, { model: null as unknown as string })} />
        {models.length === 0 ? <p>Loading models…</p> : null}
        {models.map((m) => (
          <Opt key={m.id} title={m.label || m.name || m.id} sub={m.label && m.label !== m.id ? m.id : undefined} right={chat.model === m.id ? '✓' : null} onClick={() => { void updateChat(chatId, { model: m.id }); setSheet(''); }} />
        ))}
        {chat.backend === 'claude' ? (
          <>
            <h2 style={{ marginTop: 18 }}>Effort</h2>
            {['', 'low', 'medium', 'high'].map((e) => (
              <Opt key={e || 'default'} title={e ? e[0].toUpperCase() + e.slice(1) : 'Default'} right={(chat.effort ?? '') === e ? '✓' : null} onClick={() => void updateChat(chatId, { effort: (e || null) as unknown as string })} />
            ))}
          </>
        ) : null}
      </Sheet>

      <Sheet open={sheet === 'menu'} onClose={() => setSheet('')}>
        <h2>{chat.title}</h2>
        {!isInbox && <Opt title="Rename" onClick={() => { setRenameTo(chat.title); setSheet('rename'); }} />}
        {!isInbox && <Opt title={goal ? 'Goal' : 'Set a goal'} sub={goal?.objective ? String(goal.objective) : 'The agent keeps going until a judge says it’s done'} onClick={() => { setGoalText(String(goal?.objective ?? '')); setSheet('goal'); }} />}
        {!isInbox && <Opt title="Context and cost" onClick={() => { void refreshContext(); setSheet('context'); }} />}
        <Opt title="Check for a lost reply" sub="Asks the Mac about anything that looks stuck" onClick={() => { void reconcile(chatId); void loadThread(chat); setSheet(''); }} />
        <Opt title={chat.pinned ? 'Unpin' : 'Pin'} onClick={() => { void updateChat(chatId, { pinned: !chat.pinned }); setSheet(''); }} />
        <Opt title={chat.archivedAt ? 'Unarchive' : 'Archive'} onClick={() => { void updateChat(chatId, { archivedAt: (chat.archivedAt ? null : Date.now()) as unknown as number }); setSheet(''); }} />
        <Opt title="Export as text" onClick={() => { exportChat(); setSheet(''); }} />
        {!isInbox && <Opt title="New chat" onClick={() => { setSheet(''); void newChat(); }} />}
        <Opt title="Delete" danger onClick={() => onCommand('delete', '')} />
      </Sheet>

      <Sheet open={sheet === 'rename'} onClose={() => setSheet('')}>
        <h2>Rename</h2>
        <input type="text" value={renameTo} onChange={(e) => setRenameTo(e.target.value)} autoFocus />
        <div className="btnrow">
          <button type="button" className="btn ghost" onClick={() => setSheet('')}>Cancel</button>
          <button type="button" className="btn" disabled={!renameTo.trim()} onClick={() => { void updateChat(chatId, { title: renameTo.trim() }); setSheet(''); }}>Save</button>
        </div>
      </Sheet>

      <Sheet open={sheet === 'goal'} onClose={() => setSheet('')}>
        <h2>Goal</h2>
        {goal ? (
          <>
            <p>
              <b style={{ color: 'var(--ink)' }}>{String(goal.objective ?? '')}</b>
              <br />
              {String(goal.status ?? '')}{goal.loop ? ' · keeps going' : ' · advisory'}{typeof goal.continuations === 'number' ? ` · ${goal.continuations} nudges` : ''}
            </p>
            <div className="btnrow">
              {goal.status === 'active' ? <button type="button" className="btn ghost" onClick={() => void goalControl('pause')}>Pause</button> : <button type="button" className="btn ghost" onClick={() => void goalControl('resume')}>Resume</button>}
              <button type="button" className="btn danger" onClick={() => void goalControl('remove')}>Clear</button>
            </div>
            <h2 style={{ marginTop: 18 }}>Replace it</h2>
          </>
        ) : (
          <p>Say what “done” looks like. After each reply a judge model checks it; with “keep going” on, the agent is nudged to continue until it’s done or stuck.</p>
        )}
        <textarea className="field" rows={3} value={goalText} onChange={(e) => setGoalText(e.target.value)} placeholder="e.g. All tests pass and the PR is open" />
        <label style={{ display: 'flex', gap: 8, alignItems: 'center', marginTop: 10, fontSize: 15 }}>
          <input type="checkbox" checked={goalLoop} onChange={(e) => setGoalLoop(e.target.checked)} /> Keep going until it’s done
        </label>
        <div className="btnrow">
          <button type="button" className="btn" disabled={!goalText.trim()} onClick={() => void armGoal(goalText.trim(), goalLoop)}>Set goal</button>
        </div>
      </Sheet>

      <Sheet open={sheet === 'context'} onClose={() => setSheet('')}>
        <h2>Context</h2>
        {ctx && ctx.window ? (
          <>
            <p>
              {ctx.used.toLocaleString()} of {ctx.window.toLocaleString()} tokens ({Math.round((ctx.used / ctx.window) * 100)}%)
              {ctx.model ? ` · ${ctx.model}` : ''}
            </p>
            {typeof ctx.costUsd === 'number' ? <p>Cost so far: ${ctx.costUsd.toFixed(ctx.costUsd < 1 ? 3 : 2)}{ctx.turns ? ` over ${ctx.turns} turn${ctx.turns === 1 ? '' : 's'}` : ''}</p> : null}
            {ctx.compactAt ? <p>Auto-compacts at {ctx.compactAt.toLocaleString()} tokens.</p> : null}
          </>
        ) : (
          <p>No usage yet: send something first.</p>
        )}
        <div className="btnrow">
          <button type="button" className="btn ghost" onClick={() => { setSheet(''); void compact(); }}>Compact now</button>
        </div>
      </Sheet>
    </Screen>
  );
}
