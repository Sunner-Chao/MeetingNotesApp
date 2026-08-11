/// <reference types="vite/client" />
/// <reference types="vite-plugin-pwa/client" />

interface BeforeInstallPromptEvent extends Event {
  prompt(): Promise<void>;
  userChoice: Promise<{ outcome: "accepted" | "dismissed"; platform: string }>;
}

interface Navigator {
  standalone?: boolean;
  wakeLock?: {
    request(type: "screen"): Promise<WakeLockSentinel>;
  };
}

interface WakeLockSentinel extends EventTarget {
  released: boolean;
  release(): Promise<void>;
}
