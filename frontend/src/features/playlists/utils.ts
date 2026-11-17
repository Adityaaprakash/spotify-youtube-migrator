/**
 * Formats a duration in milliseconds into a clean mm:ss or h:mm:ss display string.
 * Handles null, undefined, negative values, and non-numeric inputs gracefully.
 */
export function formatDuration(durationMs?: number | null): string {
  if (durationMs === null || durationMs === undefined || isNaN(durationMs) || durationMs < 0) {
    return '-:--';
  }

  const totalSeconds = Math.floor(durationMs / 1000);
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;

  const paddedSeconds = seconds.toString().padStart(2, '0');

  if (hours > 0) {
    const paddedMinutes = minutes.toString().padStart(2, '0');
    return `${hours}:${paddedMinutes}:${paddedSeconds}`;
  }

  return `${minutes}:${paddedSeconds}`;
}

/**
 * Strips HTML tags or entities from playlist descriptions (sometimes present in Spotify responses).
 */
export function sanitizeDescription(description?: string | null): string {
  if (!description) return '';
  return description
    .replace(/<[^>]*>/g, '')
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .trim();
}
