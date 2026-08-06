"""Binary image validation and metadata stripping for community media."""

from __future__ import annotations

import struct


class CommunityMediaFormatError(ValueError):
    """Raised when a declared image cannot be parsed safely."""


def sanitize_image_bytes(data: bytes, mime_type: str) -> bytes:
    if mime_type == "image/jpeg":
        return _sanitize_jpeg(data)
    if mime_type == "image/png":
        return _sanitize_png(data)
    if mime_type == "image/webp":
        return _sanitize_webp(data)
    raise CommunityMediaFormatError("仅支持 JPEG、PNG 或 WebP 图片")


def _sanitize_jpeg(data: bytes) -> bytes:
    if len(data) < 4 or data[:2] != b"\xff\xd8":
        raise CommunityMediaFormatError("JPEG 文件格式无效")
    output = bytearray(data[:2])
    index = 2
    while index < len(data):
        if data[index] != 0xFF:
            raise CommunityMediaFormatError("JPEG 标记格式无效")
        marker_start = index
        while index < len(data) and data[index] == 0xFF:
            index += 1
        if index >= len(data):
            raise CommunityMediaFormatError("JPEG 标记不完整")
        marker = data[index]
        index += 1
        if marker == 0xD9:
            output.extend(data[marker_start:index])
            return bytes(output)
        if marker == 0xDA:
            # Entropy-coded image data starts after SOS and can contain arbitrary bytes.
            output.extend(data[marker_start:])
            return bytes(output)
        if marker in {0x01, *range(0xD0, 0xD8)}:
            output.extend(data[marker_start:index])
            continue
        if index + 2 > len(data):
            raise CommunityMediaFormatError("JPEG 段长度不完整")
        segment_length = int.from_bytes(data[index:index + 2], "big")
        end = index + segment_length
        if segment_length < 2 or end > len(data):
            raise CommunityMediaFormatError("JPEG 段长度无效")
        # APP1 contains EXIF/XMP and APP13 commonly contains IPTC metadata.
        if marker not in {0xE1, 0xED}:
            output.extend(data[marker_start:end])
        index = end
    raise CommunityMediaFormatError("JPEG 缺少图像结束标记")


def _sanitize_png(data: bytes) -> bytes:
    signature = b"\x89PNG\r\n\x1a\n"
    if len(data) < len(signature) or data[:8] != signature:
        raise CommunityMediaFormatError("PNG 文件格式无效")
    output = bytearray(signature)
    index = len(signature)
    has_iend = False
    while index < len(data):
        if index + 12 > len(data):
            raise CommunityMediaFormatError("PNG 数据块不完整")
        length = struct.unpack(">I", data[index:index + 4])[0]
        chunk_type = data[index + 4:index + 8]
        end = index + 12 + length
        if end > len(data):
            raise CommunityMediaFormatError("PNG 数据块长度无效")
        if chunk_type not in {b"eXIf", b"tEXt", b"iTXt", b"zTXt"}:
            output.extend(data[index:end])
        index = end
        if chunk_type == b"IEND":
            has_iend = True
            break
    if not has_iend or index != len(data):
        raise CommunityMediaFormatError("PNG 图像结束标记无效")
    return bytes(output)


def _sanitize_webp(data: bytes) -> bytes:
    if len(data) < 12 or data[:4] != b"RIFF" or data[8:12] != b"WEBP":
        raise CommunityMediaFormatError("WebP 文件格式无效")
    output = bytearray(b"RIFF\x00\x00\x00\x00WEBP")
    index = 12
    while index < len(data):
        if index + 8 > len(data):
            raise CommunityMediaFormatError("WebP 数据块不完整")
        chunk_type = data[index:index + 4]
        length = struct.unpack("<I", data[index + 4:index + 8])[0]
        padded_length = length + (length % 2)
        end = index + 8 + padded_length
        if end > len(data):
            raise CommunityMediaFormatError("WebP 数据块长度无效")
        # EXIF and XMP may disclose the capture location or other metadata.
        if chunk_type not in {b"EXIF", b"XMP "}:
            output.extend(data[index:end])
        index = end
    if index != len(data):
        raise CommunityMediaFormatError("WebP 尾部数据无效")
    output[4:8] = struct.pack("<I", len(output) - 8)
    return bytes(output)
