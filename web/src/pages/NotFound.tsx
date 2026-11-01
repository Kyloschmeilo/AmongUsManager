import { Link } from 'react-router-dom';

export function NotFound() {
  return (
    <div className="page">
      <div className="panel empty">
        <span className="eyebrow">404</span>
        <p className="ink">Diese Seite gibt es nicht.</p>
        <Link className="btn" to="/">Zur Übersicht</Link>
      </div>
    </div>
  );
}
