package com.fluxo.user;

import com.fluxo.auth.AuthUser;
import com.fluxo.common.Errors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/** Uploaded avatars (ADD_FUNCTIONALITIES): squared, resized to 128 px PNG, shown on boards and tickets. */
@RestController
@RequestMapping("/api")
class AvatarController {

    static final int SIZE = 128;
    static final long MAX_BYTES = 5L * 1024 * 1024;

    private final UserRepository users;
    private final Path root;

    AvatarController(UserRepository users, @Value("${fluxo.storage-dir}") String storageDir) throws IOException {
        this.users = users;
        this.root = Files.createDirectories(Path.of(storageDir));
    }

    @PostMapping("/me/avatar")
    @Transactional
    Map<String, String> upload(@AuthenticationPrincipal AuthUser me, @RequestParam MultipartFile file) throws IOException {
        if (file.getSize() > MAX_BYTES) throw Errors.badRequest("AVATAR_TOO_LARGE", "Image de 5 Mo au plus");
        BufferedImage img;
        try (var in = file.getInputStream()) {
            img = ImageIO.read(in); // decodes the content, whatever the declared type: only real images pass
        }
        if (img == null) throw Errors.badRequest("AVATAR_FORMAT", "Image PNG, JPEG ou GIF attendue");
        int side = Math.min(img.getWidth(), img.getHeight());
        BufferedImage out = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        var g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(img, 0, 0, SIZE, SIZE, (img.getWidth() - side) / 2, (img.getHeight() - side) / 2,
                (img.getWidth() + side) / 2, (img.getHeight() + side) / 2, null);
        g.dispose();
        User u = users.findById(me.id()).orElseThrow();
        String key = "avatar-" + UUID.randomUUID() + ".png";
        ImageIO.write(out, "png", root.resolve(key).toFile());
        if (u.getAvatarKey() != null) Files.deleteIfExists(root.resolve(u.getAvatarKey()));
        u.setAvatarKey(key);
        return Map.of("avatar", u.avatarUrl());
    }

    @DeleteMapping("/me/avatar")
    @Transactional
    void remove(@AuthenticationPrincipal AuthUser me) throws IOException {
        User u = users.findById(me.id()).orElseThrow();
        if (u.getAvatarKey() != null) Files.deleteIfExists(root.resolve(u.getAvatarKey()));
        u.setAvatarKey(null);
    }

    /** The URL carries a version, so the image is cached for a year. */
    @GetMapping("/users/{id}/avatar")
    ResponseEntity<FileSystemResource> get(@PathVariable UUID id) {
        User u = users.findById(id).filter(x -> x.getAvatarKey() != null).orElseThrow(() -> Errors.notFound("Pas d'avatar"));
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePrivate())
                .body(new FileSystemResource(root.resolve(u.getAvatarKey())));
    }
}
