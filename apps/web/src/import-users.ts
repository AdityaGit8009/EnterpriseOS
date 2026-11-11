/** Parse pasted CSV without silently discarding quoted values or malformed rows. */
export function parseUserImport(text: string): { email: string; role: string }[] {
  const rows: string[][] = [];
  let row: string[] = [],
    cell = '',
    quoted = false;
  for (let i = 0; i < text.length; i++) {
    const char = text[i];
    if (char === '"') {
      if (quoted && text[i + 1] === '"') {
        cell += '"';
        i++;
      } else if (!quoted && cell.trim())
        throw new Error('A quoted value must begin at the start of a CSV field.');
      else quoted = !quoted;
    } else if (char === ',' && !quoted) {
      row.push(cell.trim());
      cell = '';
    } else if ((char === '\n' || char === '\r') && !quoted) {
      if (char === '\r' && text[i + 1] === '\n') i++;
      row.push(cell.trim());
      if (row.some(Boolean)) rows.push(row);
      row = [];
      cell = '';
    } else cell += char;
  }
  if (quoted) throw new Error('A quoted CSV value was not closed.');
  row.push(cell.trim());
  if (row.some(Boolean)) rows.push(row);
  if (rows[0]?.[0].toLowerCase() === 'email') rows.shift();
  if (!rows.length) throw new Error('Paste at least one email,role row.');
  if (rows.length > 200) throw new Error('Import at most 200 users at a time.');
  return rows.map((r, i) => {
    if (r.length !== 2)
      throw new Error('Row ' + (i + 1) + ' needs exactly two columns: email,role.');
    return { email: r[0], role: r[1].toUpperCase() };
  });
}
