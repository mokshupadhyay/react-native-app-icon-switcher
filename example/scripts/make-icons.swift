// Draws the example's 1024x1024 icons: swift scripts/make-icons.swift <outDir>
import AppKit
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers

struct Icon {
  let name: String
  let top: NSColor
  let bottom: NSColor
  let glyph: String
}

let icons = [
  Icon(name: "Primary", top: NSColor(red: 0.20, green: 0.55, blue: 1.00, alpha: 1),
       bottom: NSColor(red: 0.10, green: 0.30, blue: 0.85, alpha: 1), glyph: "A"),
  Icon(name: "Midnight", top: NSColor(red: 0.16, green: 0.14, blue: 0.32, alpha: 1),
       bottom: NSColor(red: 0.02, green: 0.02, blue: 0.08, alpha: 1), glyph: "M"),
  Icon(name: "Sunset", top: NSColor(red: 1.00, green: 0.62, blue: 0.20, alpha: 1),
       bottom: NSColor(red: 0.90, green: 0.20, blue: 0.40, alpha: 1), glyph: "S"),
]

let outDir = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "."
let size = 1024

for icon in icons {
  let context = CGContext(
    data: nil, width: size, height: size, bitsPerComponent: 8, bytesPerRow: 0,
    space: CGColorSpace(name: CGColorSpace.sRGB)!,
    bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
  let gradient = CGGradient(
    colorsSpace: CGColorSpace(name: CGColorSpace.sRGB),
    colors: [icon.top.cgColor, icon.bottom.cgColor] as CFArray, locations: [0, 1])!
  context.drawLinearGradient(
    gradient, start: CGPoint(x: 0, y: size), end: CGPoint(x: 0, y: 0), options: [])

  NSGraphicsContext.current = NSGraphicsContext(cgContext: context, flipped: false)
  let attributes: [NSAttributedString.Key: Any] = [
    .font: NSFont.systemFont(ofSize: 620, weight: .heavy),
    .foregroundColor: NSColor.white,
  ]
  let text = NSAttributedString(string: icon.glyph, attributes: attributes)
  let bounds = text.size()
  text.draw(at: NSPoint(x: (CGFloat(size) - bounds.width) / 2, y: (CGFloat(size) - bounds.height) / 2))
  NSGraphicsContext.current = nil

  let url = URL(fileURLWithPath: outDir).appendingPathComponent("\(icon.name).png")
  let destination = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil)!
  CGImageDestinationAddImage(destination, context.makeImage()!, nil)
  CGImageDestinationFinalize(destination)
  print(url.path)
}
