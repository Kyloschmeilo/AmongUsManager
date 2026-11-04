// Bewegung: React-Bits-Bausteine (SplitText, AnimatedContent, CountUp) mit den Einstellungen der Seite,
// Lenis (weiches Scrollen) und ein Helfer, der Balken beim Hineinscrollen wachsen lässt.
// Wer „Bewegung reduzieren“ eingestellt hat, sieht alles sofort und ohne Animation.
import { useEffect, type ReactNode, type RefObject } from 'react';
import { gsap } from 'gsap';
import { ScrollTrigger } from 'gsap/ScrollTrigger';
import Lenis from 'lenis';
import { useLocation } from 'react-router-dom';
import CountUp from '../reactbits/CountUp';

gsap.registerPlugin(ScrollTrigger);

export const reducedMotion = (): boolean =>
  typeof window !== 'undefined' && (window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false);

let lenis: Lenis | null = null;

/** Weiches Scrollen mit Lenis, gekoppelt an den GSAP-Takt (damit ScrollTrigger synchron bleibt). */
export function useSmoothScroll(): void {
  const location = useLocation();
  useEffect(() => {
    if (reducedMotion()) return;
    const instance = new Lenis({ duration: 1.05, smoothWheel: true });
    lenis = instance;
    instance.on('scroll', ScrollTrigger.update);
    const tick = (time: number) => instance.raf(time * 1000);
    gsap.ticker.add(tick);
    gsap.ticker.lagSmoothing(0);
    return () => {
      gsap.ticker.remove(tick);
      instance.destroy();
      lenis = null;
    };
  }, []);
  useEffect(() => {
    if (lenis) lenis.scrollTo(0, { immediate: true });
    else window.scrollTo(0, 0);
    const id = window.setTimeout(() => ScrollTrigger.refresh(), 300);
    return () => window.clearTimeout(id);
  }, [location.pathname]);
}

/** Seitenüberschrift. Bewusst ohne Buchstaben-Animation (wirkte nach Baukasten, Kilian 02.11.2026). */
export function Title({ text, className, tag = 'h1' }: { text: string; className?: string; tag?: 'h1' | 'h2' }) {
  const Tag = tag;
  return <Tag className={className}>{text}</Tag>;
}

/**
 * Früher glitt jeder Abschnitt beim Scrollen hoch. Jetzt steht der Inhalt sofort da; bewegt wird nur, was etwas
 * bedeutet (Zahlen zählen hoch, Balken wachsen, das Replay läuft).
 */
export function Reveal({ children, className }: { children: ReactNode; delay?: number; className?: string }) {
  return className ? <div className={className}>{children}</div> : <>{children}</>;
}

/** Ganze Zahl, die hochzählt (deutsche Tausender-Punkte). */
export function Count({ value, suffix }: { value: number; suffix?: string }) {
  return <CountUp to={Math.round(value)} separator="." duration={1.4} suffix={suffix} trigger="view" />;
}

/** Lässt alle `[data-grow]`-Elemente in [ref] beim Hineinscrollen von 0 auf ihre Größe wachsen. */
export function useGrow(ref: RefObject<HTMLElement | null>, axis: 'x' | 'y' = 'x', deps: unknown[] = []): void {
  useEffect(() => {
    const el = ref.current;
    if (!el || reducedMotion()) return;
    const targets = el.querySelectorAll('[data-grow]');
    if (!targets.length) return;
    const ctx = gsap.context(() => {
      gsap.from(targets, {
        [axis === 'x' ? 'scaleX' : 'scaleY']: 0,
        duration: 0.8,
        ease: 'power3.out',
        stagger: 0.03,
        scrollTrigger: { trigger: el, start: 'top 88%', once: true }
      });
    }, el);
    return () => ctx.revert();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);
}

/** Nach dem Laden neuer Inhalte die Scroll-Positionen neu messen. */
export function useRefreshOnChange(value: unknown): void {
  useEffect(() => {
    const id = window.setTimeout(() => ScrollTrigger.refresh(), 100);
    return () => window.clearTimeout(id);
  }, [value]);
}

/** Lässt alle `[data-stagger]`-Elemente in [ref] nacheinander einblenden – bei jeder Änderung von [deps] neu. */
export function useStagger(ref: RefObject<HTMLElement | null>, deps: unknown[] = []): void {
  useEffect(() => {
    const el = ref.current;
    if (!el || reducedMotion()) return;
    const targets = el.querySelectorAll('[data-stagger]');
    if (!targets.length) return;
    const ctx = gsap.context(() => {
      gsap.from(targets, { y: 14, autoAlpha: 0, duration: 0.45, ease: 'power3.out', stagger: 0.06 });
    }, el);
    return () => ctx.revert();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);
}

/** Weich zu einem Element scrollen (mit Lenis, sonst normal); `offset` = Platz für die Kopfzeile. */
export function scrollToElement(el: HTMLElement, offset = 84): void {
  if (lenis) lenis.scrollTo(el, { offset: -offset });
  else window.scrollTo({ top: el.getBoundingClientRect().top + window.scrollY - offset, behavior: reducedMotion() ? 'auto' : 'smooth' });
}
