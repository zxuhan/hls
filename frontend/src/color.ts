// Deterministic block-id → ASML-blue HSL mapping.
// FNV-1a 32-bit hash, then split into hue/sat/lightness within a blue band
// (cyan → indigo) so result-grid cells stay on-brand while remaining distinct.

export function colorForBlockId(id: string): string {
  let h = 0x811c9dc5
  for (let i = 0; i < id.length; i++) {
    h ^= id.charCodeAt(i)
    h = Math.imul(h, 0x01000193)
  }
  const u = h >>> 0
  const hue = 200 + (u % 30) // 200°–229°
  const sat = 55 + ((u >>> 8) % 25) // 55–79%
  const lig = 42 + ((u >>> 16) % 14) // 42–55%
  return `hsl(${hue}, ${sat}%, ${lig}%)`
}
