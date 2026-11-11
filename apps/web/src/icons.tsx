import React from 'react';
const paths: Record<string,string> = {
 dashboard:'M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h7v7h-7z',
 users:'M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2 M16 3a4 4 0 0 1 0 8 M22 21v-2a4 4 0 0 0-3-3.87 M13 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0',
 building:'M4 21V3h12v18 M16 9h4v12 M2 21h20 M8 7h4 M8 11h4 M8 15h4 M8 21v-3h4v3',
 briefcase:'M3 7h18v14H3z M8 7V3h8v4 M3 12c5 4 13 4 18 0 M10 13h4',
 calendar:'M3 5h18v16H3z M7 3v4 M17 3v4 M3 11h18 M7 15h2 M13 15h2',
 clock:'M12 8v5l3 2 M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0',
 shield:'M12 3l8 3v6c0 5-8 10-8 10S4 17 4 12V6z M8 12l3 3 5-6',
 settings:'M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8 M9 3h6l1 3 3 1 2 5-2 2v3l-5 4-3-1-3 1-5-4v-3l-2-2 2-5 3-1z',
 search:'M21 21l-5-5 M18 10a8 8 0 1 1-16 0 8 8 0 0 1 16 0',
 plus:'M12 5v14 M5 12h14', arrow:'M5 12h14 M13 6l6 6-6 6', close:'M6 6l12 12 M18 6L6 18',
 check:'M5 12l4 4L19 6', chevron:'M9 5l7 7-7 7', logout:'M9 3H3v18h6 M9 12h13 M17 7l5 5-5 5',
 activity:'M3 12h4l3-8 4 16 3-8h4', edit:'M16 3l5 5-12 12-6 1 1-6z M13 6l5 5',
 mail:'M3 5h18v14H3z M3 6l9 7 9-7', sun:'M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8 M12 2v2 M12 20v2 M2 12h2 M20 12h2 M5 5l2 2 M17 17l2 2 M5 19l2-2 M17 7l2-2',
 moon:'M21 13a9 9 0 1 1-10-10 7 7 0 0 0 10 10', menu:'M4 6h16 M4 12h16 M4 18h16',
 board:'M3 3h18v18H3z M9 3v18 M15 3v18 M5 7h2 M11 7h2 M17 7h2 M5 11h2 M11 11h2',
 leaf:'M20 3C8 3 2 7 5 15c8 6 16-2 15-12 M4 21l12-12', file:'M5 3h10l5 5v13H5z M15 3v6h5 M8 13h9 M8 17h6',
 refresh:'M20 7V3l-4 4 M20 7a9 9 0 1 0 1 8', link:'M10 13l4-4 M8 16l-2 2a4 4 0 0 1-6-6l5-5 M16 8l2-2a4 4 0 0 1 6 6l-5 5',
 filter:'M3 4h18l-7 8v7l-4 2v-9z', download:'M12 3v12 M7 10l5 5 5-5 M3 16v5h18v-5', bell:'M5 17h14l-2-4V8a5 5 0 0 0-10 0v5z M10 21h4',
 info:'M12 11v6 M12 7v1 M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0'
};
export function Icon({name,size=20,...props}:{name:string,size?:number}&React.SVGProps<SVGSVGElement>){return <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" {...props}><path d={paths[name]||paths.file}/></svg>}
