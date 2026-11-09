// Self-authored Mapper 246 test ROM. The backdrop color comes from CPU RAM $6800.
// With no cheats it is blue (01). A VCT code 6800-01-16 changes it to red (16).
const fs = require('node:fs');
const output = process.argv[2];
if (!output) throw new Error('Pass output .nes path');
const rom = Buffer.alloc(16 + 32768 + 8192);
rom.set([0x4e,0x45,0x53,0x1a,2,1,0x62,0xf0]);
const code = [];
const emit = (...bytes) => code.push(...bytes);
emit(0x78,0xd8,0xa2,0xff,0x9a); // SEI, CLD, LDX #FF, TXS
emit(0xa9,0x00,0x8d,0x00,0x20,0x8d,0x01,0x20); // disable rendering/NMI
emit(0xa9,0x01,0x8d,0x00,0x68); // initial RAM value
const loop = code.length;
emit(0x2c,0x02,0x20); // BIT $2002
emit(0x10, (loop-(code.length+2)) & 255); // BPL wait for vblank
emit(0xa9,0x3f,0x8d,0x06,0x20,0xa9,0x00,0x8d,0x06,0x20);
emit(0xad,0x00,0x68,0x8d,0x07,0x20); // write RAM value to palette $3F00
emit(0xa9,0x00,0x8d,0x06,0x20,0x8d,0x06,0x20);
emit(0x4c,loop & 255,0xe0);
rom.set(code,16+32768-8192);
for(let offset=32768-6;offset<32768;offset+=2) rom.set([0,0xe0],16+offset);
fs.writeFileSync(output,rom);
console.log(`Created ${output}: ${rom.length} bytes, Mapper 246`);
