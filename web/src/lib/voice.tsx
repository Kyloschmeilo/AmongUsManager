// Proximity-Voice (StatsWebsite.jar → web/VoiceChat.kt): Sprache per WebRTC direkt zwischen den Browsern (Mesh),
// die Website vermittelt nur (SSE + POST) und sagt, wie laut man wen hört (aus den Positionen im Spiel, die nur der
// Server kennt). Bleibt beim Seitenwechsel verbunden (Provider in App.tsx).
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, useSyncExternalStore, type ReactNode } from 'react';
import { postJson } from './api';

export type VoiceMode = 'lobby' | 'round' | 'meeting' | 'free';
export type VoicePhase = 'idle' | 'connecting' | 'live' | 'ended';
export type PeerConnection = 'new' | 'connecting' | 'connected' | 'failed';

export interface VoicePeer {
  id: string;
  name: string;
  color: number | null;
  key: string | null;
  connection: PeerConnection;
  /** Spricht gerade hörbar (nach der Lautstärke, die man von ihm hört – verrät also nichts zusätzlich). */
  speaking: boolean;
}

export interface VoiceSnapshot {
  phase: VoicePhase;
  mode: VoiceMode | null;
  peers: VoicePeer[];
  muted: boolean;
  speaking: boolean;
  volume: number;
  /** Hinweis/Fehler zum Anzeigen, z.B. warum die Verbindung endete. */
  message: string | null;
}

export interface VoiceStatus {
  enabled: boolean;
  loggedIn: boolean;
  linked?: boolean;
  room?: { code: string; mode: VoiceMode; members: number; players: number } | null;
  peer?: string | null;
}

interface Signal {
  sdp?: RTCSessionDescriptionInit;
  candidate?: RTCIceCandidateInit | null;
}

interface PeerState {
  id: string;
  name: string;
  color: number | null;
  key: string | null;
  offer: boolean;
  pc: RTCPeerConnection | null;
  transceiver: RTCRtpTransceiver | null;
  pending: RTCIceCandidateInit[];
  audio: HTMLAudioElement | null;
  source: MediaStreamAudioSourceNode | null;
  gain: GainNode | null;
  analyser: AnalyserNode | null;
  hear: number;
  send: boolean;
  sending: boolean;
  connection: PeerConnection;
  speaking: boolean;
  restarts: number;
}

const BYE_TEXT: Record<string, string> = {
  replaced: 'Voice läuft jetzt in einem anderen Tab.',
  'left-lobby': 'Du bist im Spiel nicht mehr in dieser Lobby.',
  'host-gone': 'Der Host meldet sich nicht mehr – Voice beendet.',
  timeout: 'Verbindung zur Website verloren.',
};

export function voiceSupported(): string | null {
  if (typeof window === 'undefined') return 'Kein Browser';
  if (!window.isSecureContext) return 'Das Mikrofon gibt der Browser nur auf https-Seiten frei. Öffne die Website über ihre https-Adresse.';
  if (!navigator.mediaDevices?.getUserMedia || typeof RTCPeerConnection === 'undefined') return 'Dein Browser kann kein WebRTC.';
  return null;
}

class VoiceEngine {
  private snapshot: VoiceSnapshot = { phase: 'idle', mode: null, peers: [], muted: false, speaking: false, volume: 1, message: null };
  private listeners = new Set<() => void>();
  private peers = new Map<string, PeerState>();
  private me: string | null = null;
  private ice: RTCIceServer[] = [];
  private events: EventSource | null = null;
  private stream: MediaStream | null = null;
  private track: MediaStreamTrack | null = null;
  private ctx: AudioContext | null = null;
  private master: GainNode | null = null;
  private micAnalyser: AnalyserNode | null = null;
  private meter: number | null = null;
  private muted = false;
  private volume = 1;
  private mode: VoiceMode | null = null;

  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  get = () => this.snapshot;

  private emit(patch: Partial<VoiceSnapshot> = {}) {
    this.snapshot = {
      ...this.snapshot,
      mode: this.mode,
      muted: this.muted,
      volume: this.volume,
      peers: [...this.peers.values()].map((p) => ({
        id: p.id, name: p.name, color: p.color, key: p.key, connection: p.connection, speaking: p.speaking,
      })),
      ...patch,
    };
    this.listeners.forEach((l) => l());
  }

  async join() {
    if (this.snapshot.phase === 'connecting' || this.snapshot.phase === 'live') return;
    const unsupported = voiceSupported();
    if (unsupported) {
      this.emit({ phase: 'ended', message: unsupported });
      return;
    }
    this.emit({ phase: 'connecting', message: null });
    try {
      // AudioContext im Klick anlegen, sonst bleibt er wegen Autoplay-Regeln stumm
      this.ctx = new AudioContext();
      this.master = this.ctx.createGain();
      this.master.gain.value = this.volume;
      this.master.connect(this.ctx.destination);
      this.stream = await navigator.mediaDevices.getUserMedia({
        audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true },
        video: false,
      });
      this.track = this.stream.getAudioTracks()[0] ?? null;
      if (this.track) this.track.enabled = !this.muted;
      this.micAnalyser = this.ctx.createAnalyser();
      this.micAnalyser.fftSize = 512;
      this.ctx.createMediaStreamSource(this.stream).connect(this.micAnalyser);
      const joined = await postJson<{ peer: string; ice: RTCIceServer[] }>('/api/voice/join');
      this.me = joined.peer;
      this.ice = joined.ice;
      this.openEvents();
      this.meter = window.setInterval(() => this.measure(), 120);
      window.addEventListener('pagehide', this.onPageHide);
    } catch (e) {
      const error = e as Error;
      const text = error.name === 'NotAllowedError'
        ? 'Kein Zugriff aufs Mikrofon – erlaube es in der Adressleiste und versuch es nochmal.'
        : error.name === 'NotFoundError' ? 'Kein Mikrofon gefunden.' : error.message;
      this.cleanup(text);
    }
  }

  leave() {
    if (this.me) void postJson('/api/voice/leave', { peer: this.me }).catch(() => undefined);
    this.cleanup(null);
  }

  setMuted(muted: boolean) {
    this.muted = muted;
    if (this.track) this.track.enabled = !muted;
    this.emit();
  }

  setVolume(volume: number) {
    this.volume = Math.max(0, Math.min(2, volume));
    if (this.master && this.ctx) this.master.gain.setTargetAtTime(this.volume, this.ctx.currentTime, 0.05);
    this.emit();
  }

  private onPageHide = () => {
    if (!this.me) return;
    navigator.sendBeacon?.('/api/voice/leave', new Blob([JSON.stringify({ peer: this.me })], { type: 'application/json' }));
  };

  private openEvents() {
    const es = new EventSource(`/api/voice/stream?peer=${encodeURIComponent(this.me ?? '')}`);
    this.events = es;
    es.addEventListener('hello', () => this.emit({ phase: 'live', message: null }));
    es.addEventListener('peers', (e) => this.onPeers(JSON.parse((e as MessageEvent).data).list));
    es.addEventListener('mix', (e) => this.onMix(JSON.parse((e as MessageEvent).data)));
    es.addEventListener('signal', (e) => {
      const { from, data } = JSON.parse((e as MessageEvent).data) as { from: string; data: Signal };
      void this.onSignal(from, data);
    });
    es.addEventListener('bye', (e) => {
      const reason = (JSON.parse((e as MessageEvent).data) as { reason: string }).reason;
      this.cleanup(BYE_TEXT[reason] ?? null);
    });
    es.onerror = () => {
      // EventSource verbindet selbst neu; geschlossen = der Server kennt uns nicht mehr
      if (es.readyState === EventSource.CLOSED) this.cleanup('Verbindung zur Website verloren.');
    };
  }

  private onPeers(list: { id: string; name: string; color: number | null; key: string | null; offer: boolean }[]) {
    const ids = new Set(list.map((p) => p.id));
    for (const [id, peer] of this.peers) {
      if (!ids.has(id)) {
        this.closePeer(peer);
        this.peers.delete(id);
      }
    }
    for (const info of list) {
      const known = this.peers.get(info.id);
      if (known) {
        Object.assign(known, { name: info.name, color: info.color, key: info.key, offer: info.offer });
        continue;
      }
      const peer = this.newPeer(info.id, info);
      if (info.offer) void this.startOffer(peer);
    }
    this.emit();
  }

  private newPeer(id: string, info?: { name: string; color: number | null; key: string | null; offer: boolean }): PeerState {
    const peer: PeerState = {
      id, name: info?.name ?? 'Spieler', color: info?.color ?? null, key: info?.key ?? null, offer: info?.offer ?? false,
      pc: null, transceiver: null, pending: [], audio: null, source: null, gain: null, analyser: null,
      hear: 0, send: false, sending: false, connection: 'new', speaking: false, restarts: 0,
    };
    this.peers.set(id, peer);
    return peer;
  }

  private createPc(peer: PeerState): RTCPeerConnection {
    const pc = new RTCPeerConnection({ iceServers: this.ice });
    peer.pc = pc;
    pc.onicecandidate = (e) => this.signal(peer.id, { candidate: e.candidate ? e.candidate.toJSON() : null });
    pc.ontrack = (e) => this.attachAudio(peer, e.streams[0] ?? new MediaStream([e.track]));
    pc.onconnectionstatechange = () => {
      const state = pc.connectionState;
      peer.connection = state === 'connected' ? 'connected' : state === 'failed' ? 'failed' : state === 'new' ? 'new' : 'connecting';
      this.emit();
      // nur wer anbietet, startet neu (sonst bieten beide gleichzeitig an)
      if (state === 'failed' && peer.offer && peer.restarts < 3 && this.peers.get(peer.id) === peer) {
        peer.restarts++;
        window.setTimeout(() => void this.startOffer(peer, true), 1500);
      }
    };
    return pc;
  }

  private async startOffer(peer: PeerState, restart = false) {
    try {
      const pc = peer.pc ?? this.createPc(peer);
      if (!peer.transceiver) {
        peer.transceiver = pc.addTransceiver('audio', { direction: 'sendrecv' });
        await this.applySend(peer, true);
      }
      const offer = await pc.createOffer(restart ? { iceRestart: true } : undefined);
      await pc.setLocalDescription(offer);
      this.signal(peer.id, { sdp: pc.localDescription!.toJSON() });
    } catch (e) {
      console.warn('Voice: Angebot fehlgeschlagen', e);
    }
  }

  private async onSignal(from: string, data: Signal) {
    const peer = this.peers.get(from) ?? this.newPeer(from);
    try {
      if (data.sdp) {
        const pc = peer.pc ?? this.createPc(peer);
        await pc.setRemoteDescription(data.sdp);
        if (data.sdp.type === 'offer') {
          if (!peer.transceiver) {
            peer.transceiver = pc.getTransceivers().find((t) => t.receiver.track.kind === 'audio') ?? null;
            if (peer.transceiver) peer.transceiver.direction = 'sendrecv';
            await this.applySend(peer, true);
          }
          const answer = await pc.createAnswer();
          await pc.setLocalDescription(answer);
          this.signal(peer.id, { sdp: pc.localDescription!.toJSON() });
        }
        for (const candidate of peer.pending.splice(0)) await pc.addIceCandidate(candidate).catch(() => undefined);
      } else if (data.candidate !== undefined) {
        if (!data.candidate) return;
        if (peer.pc?.remoteDescription) await peer.pc.addIceCandidate(data.candidate).catch(() => undefined);
        else peer.pending.push(data.candidate);
      }
    } catch (e) {
      console.warn('Voice: Signal nicht verarbeitet', e);
    }
  }

  private signal(to: string, data: Signal) {
    if (!this.me) return;
    void postJson('/api/voice/signal', { peer: this.me, to, data }).catch(() => undefined);
  }

  private attachAudio(peer: PeerState, stream: MediaStream) {
    if (!this.ctx || !this.master) return;
    peer.source?.disconnect();
    // Chrome spielt WebRTC-Ton in Web Audio nur ab, wenn der Stream auch an einem (stummen) Audio-Element hängt
    const audio = peer.audio ?? new Audio();
    audio.muted = true;
    audio.srcObject = stream;
    void audio.play().catch(() => undefined);
    peer.audio = audio;
    peer.source = this.ctx.createMediaStreamSource(stream);
    peer.gain = peer.gain ?? this.ctx.createGain();
    peer.gain.gain.value = peer.hear;
    peer.analyser = peer.analyser ?? this.ctx.createAnalyser();
    peer.analyser.fftSize = 512;
    peer.source.connect(peer.gain);
    peer.gain.connect(peer.analyser);
    peer.gain.connect(this.master);
  }

  private onMix(mix: { mode: VoiceMode; hear: Record<string, number>; send: string[] }) {
    this.mode = mix.mode;
    const send = new Set(mix.send);
    for (const peer of this.peers.values()) {
      peer.hear = mix.hear[peer.id] ?? 0;
      if (peer.gain && this.ctx) peer.gain.gain.setTargetAtTime(peer.hear, this.ctx.currentTime, 0.08);
      peer.send = send.has(peer.id);
      void this.applySend(peer);
    }
    this.emit();
  }

  /** Nur an Teilnehmer senden, die einen hören dürfen – wer weit weg ist, bekommt gar keinen Ton. */
  private async applySend(peer: PeerState, force = false) {
    const sender = peer.transceiver?.sender;
    if (!sender || (!force && peer.sending === peer.send)) return;
    peer.sending = peer.send;
    try {
      await sender.replaceTrack(peer.send ? this.track : null);
    } catch (e) {
      console.warn('Voice: Senden nicht umgestellt', e);
    }
  }

  private measure() {
    let changed = false;
    const speaking = !this.muted && level(this.micAnalyser) > 0.035;
    if (speaking !== this.snapshot.speaking) changed = true;
    for (const peer of this.peers.values()) {
      const now = level(peer.analyser) > 0.02;
      if (now !== peer.speaking) {
        peer.speaking = now;
        changed = true;
      }
    }
    if (changed) this.emit({ speaking });
  }

  private closePeer(peer: PeerState) {
    peer.pc?.close();
    peer.source?.disconnect();
    peer.gain?.disconnect();
    if (peer.audio) peer.audio.srcObject = null;
  }

  private cleanup(message: string | null) {
    this.events?.close();
    this.events = null;
    for (const peer of this.peers.values()) this.closePeer(peer);
    this.peers.clear();
    this.stream?.getTracks().forEach((t) => t.stop());
    this.stream = null;
    this.track = null;
    if (this.meter != null) window.clearInterval(this.meter);
    this.meter = null;
    void this.ctx?.close().catch(() => undefined);
    this.ctx = null;
    this.master = null;
    this.micAnalyser = null;
    this.me = null;
    this.mode = null;
    window.removeEventListener('pagehide', this.onPageHide);
    this.emit({ phase: message ? 'ended' : 'idle', message, speaking: false });
  }
}

function level(analyser: AnalyserNode | null): number {
  if (!analyser) return 0;
  const data = new Float32Array(analyser.fftSize);
  analyser.getFloatTimeDomainData(data);
  let sum = 0;
  for (const v of data) sum += v * v;
  return Math.sqrt(sum / data.length);
}

interface VoiceApi extends VoiceSnapshot {
  join: () => void;
  leave: () => void;
  setMuted: (muted: boolean) => void;
  setVolume: (volume: number) => void;
}

const VoiceContext = createContext<VoiceApi | null>(null);

export function VoiceProvider({ children }: { children: ReactNode }) {
  const engine = useRef<VoiceEngine | null>(null);
  if (!engine.current) engine.current = new VoiceEngine();
  const e = engine.current;
  const snapshot = useSyncExternalStore(e.subscribe, e.get);
  useEffect(() => () => e.leave(), [e]);
  const api = useMemo<VoiceApi>(() => ({
    ...snapshot,
    join: () => void e.join(),
    leave: () => e.leave(),
    setMuted: (m) => e.setMuted(m),
    setVolume: (v) => e.setVolume(v),
  }), [snapshot, e]);
  return <VoiceContext.Provider value={api}>{children}</VoiceContext.Provider>;
}

export function useVoice(): VoiceApi {
  const value = useContext(VoiceContext);
  if (!value) throw new Error('VoiceProvider fehlt');
  return value;
}

/** /api/voice: bin ich gerade in einer Lobby mit Voice? Fragt alle [intervalMs] neu. */
export function useVoiceStatus(intervalMs = 5000) {
  const [status, setStatus] = useState<VoiceStatus | null>(null);
  const load = useCallback(async () => {
    try {
      const response = await fetch('/api/voice', { headers: { Accept: 'application/json' }, cache: 'no-store', credentials: 'same-origin' });
      if (response.ok) setStatus((await response.json()) as VoiceStatus);
    } catch {
      // nächster Versuch
    }
  }, []);
  useEffect(() => {
    void load();
    const timer = window.setInterval(() => {
      if (document.visibilityState === 'visible') void load();
    }, intervalMs);
    return () => window.clearInterval(timer);
  }, [load, intervalMs]);
  return status;
}
