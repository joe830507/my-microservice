import { crc32, deflateSync } from 'node:zlib'

// 產生單色的 PNG（不需要圖片檔、也不需要額外套件）；準備商品圖片用
export function makePng(
  width: number,
  height: number,
  [r, g, b]: [number, number, number],
): Buffer {
  const rowLength = width * 3 + 1
  const raw = Buffer.alloc(rowLength * height)
  for (let y = 0; y < height; y++) {
    // 每一列開頭是 filter type 0（None）
    for (let x = 0; x < width; x++) {
      const offset = y * rowLength + 1 + x * 3
      raw[offset] = r
      raw[offset + 1] = g
      raw[offset + 2] = b
    }
  }

  const chunk = (type: string, data: Buffer): Buffer => {
    const length = Buffer.alloc(4)
    length.writeUInt32BE(data.length)
    const body = Buffer.concat([Buffer.from(type, 'ascii'), data])
    const crc = Buffer.alloc(4)
    crc.writeUInt32BE(crc32(body) >>> 0)
    return Buffer.concat([length, body, crc])
  }

  const header = Buffer.alloc(13)
  header.writeUInt32BE(width, 0)
  header.writeUInt32BE(height, 4)
  header[8] = 8 // 位元深度
  header[9] = 2 // 色彩類型：RGB

  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', header),
    chunk('IDAT', deflateSync(raw)),
    chunk('IEND', Buffer.alloc(0)),
  ])
}
