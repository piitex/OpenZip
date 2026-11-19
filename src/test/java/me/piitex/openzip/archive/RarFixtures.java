package me.piitex.openzip.archive;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Builds small RAR archives byte by byte. RAR can only be written by RARLAB's own software, so the tests make the
 * archives themselves: files are stored, not compressed, which is a legal RAR method and exercises the whole reading
 * path except the decoders (those belong to 7-Zip). The output was checked against 7-Zip's own {@code 7z t}.
 */
final class RarFixtures {
    private RarFixtures() {
    }

    record Entry(String name, byte[] data, boolean directory) {
        static Entry file(String name, String text) {
            return new Entry(name, text.getBytes(StandardCharsets.UTF_8), false);
        }

        static Entry file(String name, byte[] data) {
            return new Entry(name, data, false);
        }

        static Entry dir(String name) {
            return new Entry(name, new byte[0], true);
        }
    }

    private static long crc32(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }

    private static long crc32(byte[] data, int off, int len) {
        CRC32 crc = new CRC32();
        crc.update(data, off, len);
        return crc.getValue();
    }

    static byte[] rar4(List<Entry> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{'R', 'a', 'r', '!', 0x1A, 0x07, 0x00});
        // Main header: no flags, 6 reserved bytes.
        out.writeBytes(rar4Block(0x73, 0x0000, new byte[6]));
        for (Entry entry : entries) {
            byte[] name = entry.name().getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            writeLe(body, entry.data().length, 4);          // packed size
            writeLe(body, entry.data().length, 4);          // unpacked size
            body.write(2);                              // host OS: Windows
            writeLe(body, entry.directory() ? 0 : crc32(entry.data()), 4);
            writeLe(body, dosTime(2025, 6, 15, 12, 30, 0), 4);
            body.write(entry.directory() ? 20 : 29);        // version needed to unpack
            body.write(0x30);                           // method: store
            writeLe(body, name.length, 2);
            writeLe(body, entry.directory() ? 0x10 : 0x20, 4); // attributes
            body.writeBytes(name);
            // 0x8000: this block has data after it. 0xE0 in the dictionary bits means "directory".
            int flags = 0x8000 | (entry.directory() ? 0xE0 : 0);
            out.writeBytes(rar4Block(0x74, flags, body.toByteArray()));
            if (!entry.directory()) out.writeBytes(entry.data());
        }
        out.writeBytes(new byte[]{(byte) 0xC4, 0x3D, 0x7B, 0x00, 0x40, 0x07, 0x00}); // end of archive
        return out.toByteArray();
    }

    private static byte[] rar4Block(int type, int flags, byte[] body) {
        byte[] block = new byte[7 + body.length];
        block[2] = (byte) type;
        block[3] = (byte) flags;
        block[4] = (byte) (flags >> 8);
        block[5] = (byte) block.length;
        block[6] = (byte) (block.length >> 8);
        System.arraycopy(body, 0, block, 7, body.length);
        long crc = crc32(block, 2, block.length - 2);
        block[0] = (byte) crc;
        block[1] = (byte) (crc >> 8);
        return block;
    }

    private static long dosTime(int year, int month, int day, int hour, int minute, int second) {
        return ((long) (year - 1980) << 25) | ((long) month << 21) | ((long) day << 16) | (hour << 11) | (minute << 5) | (second / 2);
    }

    static byte[] rar5(List<Entry> entries) {
        return rar5(entries, null);
    }

    /** @param password if not null, every file's data is encrypted with AES-256, the way {@code rar a -p} does it */
    static byte[] rar5(List<Entry> entries, String password) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{'R', 'a', 'r', '!', 0x1A, 0x07, 0x01, 0x00});
        out.writeBytes(rar5Block(1, 0, null, -1, vint(0))); // main archive header: no archive flags
        int count = 0;
        for (Entry entry : entries) {
            byte[] name = entry.name().getBytes(StandardCharsets.UTF_8);
            boolean encrypt = password != null && !entry.directory();
            byte[] stored = entry.data();
            byte[] extra = null;
            if (encrypt) {
                byte[] salt = filled(16, 0x10 + count);
                byte[] iv = filled(16, 0x80 + count);
                count++;
                stored = encrypt(entry.data(), password, salt, iv, 10);
                extra = encryptionRecord(password, salt, iv, 10);
            }
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            int fileFlags = (entry.directory() ? 0x01 : 0) | 0x02 | (entry.directory() ? 0 : 0x04); // folder, mtime, crc
            body.writeBytes(vint(fileFlags));
            body.writeBytes(vint(entry.data().length));      // unpacked size
            body.writeBytes(vint(entry.directory() ? 0x10 : 0x20)); // attributes
            writeLe(body, 1750000000L, 4);               // mtime, Unix seconds
            if (!entry.directory()) writeLe(body, crc32(entry.data()), 4);
            body.writeBytes(vint(0));                    // compression: version 0, store
            body.writeBytes(vint(0));                    // host OS: Windows
            body.writeBytes(vint(name.length));
            body.writeBytes(name);
            out.writeBytes(rar5Block(2, 0, extra, entry.directory() ? -1 : stored.length, body.toByteArray()));
            if (!entry.directory()) out.writeBytes(stored);
        }
        out.writeBytes(rar5Block(5, 0, null, -1, vint(0))); // end of archive
        return out.toByteArray();
    }

    /** {@code dataSize < 0} means the block has no data area. */
    private static byte[] rar5Block(int type, int flags, byte[] extra, long dataSize, byte[] body) {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        header.writeBytes(vint(type));
        header.writeBytes(vint(flags | (extra != null ? 0x01 : 0) | (dataSize >= 0 ? 0x02 : 0)));
        if (extra != null) header.writeBytes(vint(extra.length));
        if (dataSize >= 0) header.writeBytes(vint(dataSize));
        header.writeBytes(body);
        if (extra != null) header.writeBytes(extra);
        byte[] bytes = header.toByteArray();
        ByteArrayOutputStream sized = new ByteArrayOutputStream();
        sized.writeBytes(vint(bytes.length));
        sized.writeBytes(bytes);
        byte[] covered = sized.toByteArray();
        ByteArrayOutputStream block = new ByteArrayOutputStream();
        writeLe(block, crc32(covered), 4);
        block.writeBytes(covered);
        return block.toByteArray();
    }

    /** The "file encryption" record of a file header's extra area. */
    private static byte[] encryptionRecord(String password, byte[] salt, byte[] iv, int log2Iterations) {
        byte[] check = passwordCheck(password, salt, log2Iterations);
        ByteArrayOutputStream rec = new ByteArrayOutputStream();
        rec.writeBytes(vint(1));                 // record type: file encryption
        rec.writeBytes(vint(0));                 // version: AES-256
        rec.writeBytes(vint(0x01));              // flags: a password check value follows
        rec.write(log2Iterations);
        rec.writeBytes(salt);
        rec.writeBytes(iv);
        rec.writeBytes(check);
        byte[] bytes = rec.toByteArray();
        ByteArrayOutputStream withSize = new ByteArrayOutputStream();
        withSize.writeBytes(vint(bytes.length));
        withSize.writeBytes(bytes);
        return withSize.toByteArray();
    }

    /** 8 bytes folded from the key derived with 32 extra rounds, then 4 bytes of the SHA-256 of those. */
    private static byte[] passwordCheck(String password, byte[] salt, int log2Iterations) {
        try {
            byte[] derived = pbkdf2(password, salt, (1 << log2Iterations) + 32);
            byte[] folded = new byte[8];
            for (int i = 0; i < derived.length; i++) folded[i % 8] ^= derived[i];
            byte[] sum = MessageDigest.getInstance("SHA-256").digest(folded);
            byte[] out = new byte[12];
            System.arraycopy(folded, 0, out, 0, 8);
            System.arraycopy(sum, 0, out, 8, 4);
            return out;
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static byte[] encrypt(byte[] data, String password, byte[] salt, byte[] iv, int log2Iterations) {
        try {
            byte[] key = pbkdf2(password, salt, 1 << log2Iterations);
            byte[] padded = new byte[(data.length + 15) / 16 * 16];
            System.arraycopy(data, 0, padded, 0, data.length);
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return cipher.doFinal(padded);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations) throws Exception {
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(new PBEKeySpec(password.toCharArray(), salt, iterations, 256)).getEncoded();
    }

    private static byte[] filled(int length, int start) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) bytes[i] = (byte) (start + i * 7);
        return bytes;
    }

    /** RAR 5's variable-length integer: seven bits at a time, low first, the top bit saying "more follows". */
    private static byte[] vint(long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        do {
            int chunk = (int) (value & 0x7F);
            value >>>= 7;
            out.write(value != 0 ? chunk | 0x80 : chunk);
        } while (value != 0);
        return out.toByteArray();
    }

    private static void writeLe(ByteArrayOutputStream out, long value, int bytes) {
        for (int i = 0; i < bytes; i++) out.write((int) (value >> (8 * i)) & 0xFF);
    }
}
