package com.enterpriseos;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.*;

/** Tenant-owned bitmap logos. Storage paths and MIME types never come from caller metadata. */
@RestController
@RequestMapping("/api/v1/org/logo")
public class LogoController {
    static final int MAX_BYTES = 512 * 1024;
    private static final byte[] PNG = {(byte)137,80,78,71,13,10,26,10};
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final Path directory;
    public LogoController(JdbcTemplate jdbc, AuditService audit, @Value("${app.logo-dir:./var/logos}") String directory) {
        this.jdbc = jdbc; this.audit = audit; this.directory = Path.of(directory).toAbsolutePath().normalize();
    }
    @PostMapping
    @Transactional
    public Map<String,Object> upload(@RequestAttribute Principal principal, @RequestBody Map<String,Object> body) {
        Inputs.requireRole(principal, "ADMIN");
        String data = Inputs.text(body, "dataUrl", 710000);
        int separator = data.indexOf(',');
        if (separator < 0 || separator > 40) throw new ApiException(400, "Choose a PNG, JPEG or WebP image.");
        String declared = data.substring(0, separator);
        if (!List.of("data:image/png;base64", "data:image/jpeg;base64", "data:image/webp;base64").contains(declared))
            throw new ApiException(400, "Only PNG, JPEG and WebP bitmap images are accepted.");
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(data.substring(separator + 1)); }
        catch (IllegalArgumentException ex) { throw new ApiException(400, "Image data is not valid base64."); }
        ImageType type = inspect(bytes);
        if (!declared.equals("data:" + type.mime + ";base64")) throw new ApiException(400, "Image content does not match its declared type.");
        String tenant = tenantUuid(principal);
        var tenants = jdbc.queryForList("SELECT status FROM tenants WHERE id=? FOR UPDATE", tenant);
        var memberships = jdbc.queryForList("SELECT m.role FROM memberships m JOIN users u ON u.id=m.user_id WHERE m.tenant_id=? AND m.user_id=? AND m.status='ACTIVE' AND u.status='ACTIVE'", tenant, principal.userId());
        if (tenants.isEmpty() || !"ACTIVE".equals(tenants.getFirst().get("status")) || memberships.isEmpty() || !"ADMIN".equals(memberships.getFirst().get("role")))
            throw new ApiException(403, "Active organization administrator access is required.");
        String previous = jdbc.queryForObject("SELECT logo_key FROM organization_profiles WHERE tenant_id=?", String.class, tenant);
        String key = tenant + "-" + UUID.randomUUID() + "." + type.extension;
        Path target = directory.resolve(key);
        try {
            Files.createDirectories(directory);
            Path temporary = Files.createTempFile(directory, "logo-", ".tmp");
            try {
                Files.write(temporary, bytes, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                try { Files.setPosixFilePermissions(temporary, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); }
                catch (UnsupportedOperationException ignored) { /* Windows uses the private storage directory ACL. */ }
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } finally { Files.deleteIfExists(temporary); }
        } catch (IOException ex) { throw new ApiException(500, "Logo storage is unavailable. Try again later."); }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) delete(target);
                else if (validKey(tenant, previous)) delete(directory.resolve(previous));
            }
        });
        jdbc.update("UPDATE organization_profiles SET logo_key=? WHERE tenant_id=?", key, tenant);
        audit.log(principal, "ORGANIZATION_LOGO_UPDATED", "tenant", tenant);
        return Map.of("logoKey", "/api/v1/org/logo", "message", "Company logo updated.");
    }
    @GetMapping
    public ResponseEntity<byte[]> read(@RequestAttribute Principal principal) {
        String tenant = tenantUuid(principal);
        String key = jdbc.queryForObject("SELECT logo_key FROM organization_profiles WHERE tenant_id=?", String.class, tenant);
        if (!validKey(tenant, key)) throw new ApiException(404, "Organization logo has not been uploaded.");
        Path path = directory.resolve(key).normalize();
        if (!path.startsWith(directory) || !Files.isRegularFile(path)) throw new ApiException(404, "Organization logo was not found.");
        try {
            if (Files.size(path) > MAX_BYTES) throw new ApiException(404, "Organization logo is unavailable.");
            byte[] bytes = Files.readAllBytes(path); ImageType type = inspect(bytes);
            return ResponseEntity.ok().contentType(MediaType.parseMediaType(type.mime)).cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff").body(bytes);
        } catch (IOException ex) { throw new ApiException(404, "Organization logo was not found."); }
    }
    private static String tenantUuid(Principal principal) {
        try { return UUID.fromString(principal.tenantId()).toString(); }
        catch (Exception ex) { throw new ApiException(403, "Organization membership is required."); }
    }
    private static boolean validKey(String tenant, String key) {
        return key != null && key.startsWith(tenant + "-") && key.matches("[0-9a-f-]{73}\\.(png|jpg|webp)");
    }
    private static void delete(Path path) { try { Files.deleteIfExists(path); } catch (IOException ignored) { /* Orphan storage can be cleaned by the operator; never interrupt commit. */ } }
    private record ImageType(String mime, String extension) { }
    static ImageType inspect(byte[] bytes) {
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new ApiException(400, "Logo must be a bitmap image no larger than 512 KB.");
        if (bytes.length >= 24 && Arrays.equals(Arrays.copyOf(bytes, 8), PNG)) {
            dimensions(ByteBuffer.wrap(bytes,16,4).getInt(), ByteBuffer.wrap(bytes,20,4).getInt());
            verifyImageReader(bytes);
            return new ImageType("image/png", "png");
        }
        if (bytes.length >= 4 && (bytes[0]&255)==255 && (bytes[1]&255)==216 && (bytes[2]&255)==255) {
            verifyImageReader(bytes); return new ImageType("image/jpeg", "jpg");
        }
        if (bytes.length >= 26 && ascii(bytes,0,"RIFF") && ascii(bytes,8,"WEBP")) {
            long riff = Integer.toUnsignedLong(ByteBuffer.wrap(bytes,4,4).order(ByteOrder.LITTLE_ENDIAN).getInt());
            if (riff != bytes.length - 8L) throw new ApiException(400, "WebP image data is incomplete.");
            int width, height;
            if (bytes.length >= 30 && ascii(bytes,12,"VP8X")) { width=1+little24(bytes,24); height=1+little24(bytes,27); }
            else if (ascii(bytes,12,"VP8L") && (bytes[20]&255)==47) { int bits=ByteBuffer.wrap(bytes,21,4).order(ByteOrder.LITTLE_ENDIAN).getInt(); width=1+(bits&0x3fff);height=1+((bits>>>14)&0x3fff); }
            else if (bytes.length >= 30 && ascii(bytes,12,"VP8 ") && (bytes[23]&255)==157 && bytes[24]==1 && bytes[25]==42) { width=((bytes[26]&255)|((bytes[27]&255)<<8))&0x3fff; height=((bytes[28]&255)|((bytes[29]&255)<<8))&0x3fff; }
            else throw new ApiException(400, "WebP bitmap header is invalid.");
            dimensions(width,height); return new ImageType("image/webp", "webp");
        }
        throw new ApiException(400, "Image content must be PNG, JPEG or WebP. SVG and HTML are not accepted.");
    }
    private static void verifyImageReader(byte[] bytes) {
        try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new ApiException(400, "The image cannot be read.");
            var reader = readers.next();
            try { reader.setInput(stream, true, true); dimensions(reader.getWidth(0),reader.getHeight(0)); }
            finally { reader.dispose(); }
        } catch (ApiException ex) { throw ex; }
        catch (IOException | RuntimeException ex) { throw new ApiException(400, "Image data is damaged or incomplete."); }
    }
    private static void dimensions(int width, int height) {
        if (width < 1 || height < 1 || width > 4096 || height > 4096 || (long)width * height > 16_000_000L)
            throw new ApiException(400, "Logo dimensions must fit within 4096 × 4096 pixels.");
    }
    private static boolean ascii(byte[] bytes,int at,String value) {
        for(int i=0;i<value.length();i++) if(bytes[at+i]!=(byte)value.charAt(i)) return false;
        return true;
    }
    private static int little24(byte[] bytes,int offset) { return (bytes[offset]&255)|((bytes[offset+1]&255)<<8)|((bytes[offset+2]&255)<<16); }
}
