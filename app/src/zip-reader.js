"use strict";

// Minimal, dependency-free ZIP reader (central-directory walk + per-entry
// stored/deflate extraction). Extracted out of the deleted codex-pet-importer.js
// — that file was mostly about importing "Codex Pet" theme packages (Codex CLI
// interop, not something this app has), but these zip-parsing primitives are
// genuinely generic and are still needed by settings-theme-importer.js for the
// "Import theme .zip" Settings feature.

const zlib = require("zlib");

function readZipEntries(buffer) {
  const eocdOffset = findEndOfCentralDirectory(buffer);
  const entryCount = buffer.readUInt16LE(eocdOffset + 10);
  const centralDirSize = buffer.readUInt32LE(eocdOffset + 12);
  const centralDirOffset = buffer.readUInt32LE(eocdOffset + 16);
  if (centralDirOffset + centralDirSize > buffer.length) throw new Error("zip central directory is out of bounds");

  const entries = [];
  let offset = centralDirOffset;
  for (let i = 0; i < entryCount; i += 1) {
    if (buffer.readUInt32LE(offset) !== 0x02014b50) throw new Error("invalid zip central directory");
    const flags = buffer.readUInt16LE(offset + 8);
    const method = buffer.readUInt16LE(offset + 10);
    const compressedSize = buffer.readUInt32LE(offset + 20);
    const uncompressedSize = buffer.readUInt32LE(offset + 24);
    const nameLength = buffer.readUInt16LE(offset + 28);
    const extraLength = buffer.readUInt16LE(offset + 30);
    const commentLength = buffer.readUInt16LE(offset + 32);
    const localHeaderOffset = buffer.readUInt32LE(offset + 42);
    const nameStart = offset + 46;
    const rawName = buffer.slice(nameStart, nameStart + nameLength);
    const name = normalizeZipEntryName(rawName.toString(flags & 0x0800 ? "utf8" : "utf8"));
    if (flags & 0x0001) throw new Error(`encrypted zip entries are not supported: ${name}`);
    if (compressedSize === 0xffffffff || uncompressedSize === 0xffffffff || localHeaderOffset === 0xffffffff) {
      throw new Error("zip64 packages are not supported");
    }
    entries.push({
      name,
      directory: name.endsWith("/"),
      method,
      compressedSize,
      uncompressedSize,
      localHeaderOffset,
    });
    offset = nameStart + nameLength + extraLength + commentLength;
  }
  return entries;
}

function findEndOfCentralDirectory(buffer) {
  const min = Math.max(0, buffer.length - 0xffff - 22);
  for (let offset = buffer.length - 22; offset >= min; offset -= 1) {
    if (buffer.readUInt32LE(offset) === 0x06054b50) return offset;
  }
  throw new Error("invalid zip package: missing central directory");
}

function extractZipEntry(buffer, entry, maxBytes) {
  const offset = entry.localHeaderOffset;
  if (offset + 30 > buffer.length || buffer.readUInt32LE(offset) !== 0x04034b50) {
    throw new Error(`invalid zip local header for ${entry.name}`);
  }
  if (entry.uncompressedSize > maxBytes) throw new Error(`zip entry exceeds ${maxBytes} bytes: ${entry.name}`);
  const nameLength = buffer.readUInt16LE(offset + 26);
  const extraLength = buffer.readUInt16LE(offset + 28);
  const dataStart = offset + 30 + nameLength + extraLength;
  const dataEnd = dataStart + entry.compressedSize;
  if (dataEnd > buffer.length) throw new Error(`zip entry data is out of bounds: ${entry.name}`);
  const compressed = buffer.slice(dataStart, dataEnd);
  let output;
  if (entry.method === 0) output = compressed;
  else if (entry.method === 8) output = inflateRawZipEntry(compressed, maxBytes, entry.name);
  else throw new Error(`unsupported zip compression method ${entry.method} for ${entry.name}`);
  if (output.length !== entry.uncompressedSize) throw new Error(`zip entry size mismatch: ${entry.name}`);
  if (output.length > maxBytes) throw new Error(`zip entry exceeds ${maxBytes} bytes: ${entry.name}`);
  return output;
}

function inflateRawZipEntry(compressed, maxBytes, entryName) {
  try {
    return zlib.inflateRawSync(compressed, { maxOutputLength: maxBytes });
  } catch (error) {
    if (error && error.code === "ERR_BUFFER_TOO_LARGE") {
      throw new Error(`zip entry exceeds ${maxBytes} bytes: ${entryName}`);
    }
    throw error;
  }
}

function normalizeZipEntryName(name) {
  const normalized = String(name || "").replace(/\\/g, "/").replace(/^\/+/, "");
  if (!normalized) throw new Error("zip entry has an empty name");
  if (/^[a-zA-Z]:\//.test(normalized)) throw new Error(`zip entry uses an absolute path: ${name}`);
  const parts = normalized.split("/");
  if (parts.some((part) => part === "" || part === "." || part === "..")) {
    if (normalized.endsWith("/") && parts[parts.length - 1] === "") {
      const dirParts = parts.slice(0, -1);
      if (!dirParts.some((part) => part === "" || part === "." || part === "..")) return `${dirParts.join("/")}/`;
    }
    throw new Error(`unsafe zip entry path: ${name}`);
  }
  return normalized;
}

module.exports = {
  readZipEntries,
  findEndOfCentralDirectory,
  extractZipEntry,
  inflateRawZipEntry,
  normalizeZipEntryName,
};
