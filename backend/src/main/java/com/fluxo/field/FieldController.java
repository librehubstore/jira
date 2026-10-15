package com.fluxo.field;

import com.fluxo.auth.AuthUser;
import com.fluxo.common.Errors;
import com.fluxo.space.Perm;
import com.fluxo.space.PermissionService;
import com.fluxo.space.Space;
import com.fluxo.space.SpaceRepository;
import com.fluxo.ticket.TicketType;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
class FieldController {

    private final FieldService service;
    private final SpaceRepository spaces;
    private final PermissionService permissions;

    FieldController(FieldService service, SpaceRepository spaces, PermissionService permissions) {
        this.service = service;
        this.spaces = spaces;
        this.permissions = permissions;
    }

    record FieldRequest(String name, FieldService.Type type, List<String> options) {}

    record SpaceFields(List<FieldService.Enabled> enabled, List<FieldService.Config> configs) {}

    /** Definitions are readable by everyone: tickets show their values. */
    @GetMapping("/custom-fields")
    List<FieldService.CustomField> all() {
        return service.all();
    }

    @PostMapping("/admin/custom-fields")
    @ResponseStatus(HttpStatus.CREATED)
    FieldService.CustomField create(@AuthenticationPrincipal AuthUser me, @RequestBody FieldRequest req) {
        return service.create(me, req.name(), req.type(), req.options());
    }

    @PatchMapping("/admin/custom-fields/{id}")
    FieldService.CustomField update(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id, @RequestBody FieldRequest req) {
        return service.update(me, id, req.name(), req.options());
    }

    @DeleteMapping("/admin/custom-fields/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id) {
        service.delete(me, id);
    }

    @GetMapping("/spaces/{key}/fields")
    @Transactional(readOnly = true)
    SpaceFields fields(@AuthenticationPrincipal AuthUser me, @PathVariable String key) {
        Space s = space(me, key);
        return new SpaceFields(service.enabled(s), service.configs(s));
    }

    @PutMapping("/spaces/{key}/fields/enabled")
    @Transactional
    SpaceFields setEnabled(@AuthenticationPrincipal AuthUser me, @PathVariable String key, @RequestBody List<FieldService.Enabled> list) {
        Space s = space(me, key);
        service.setEnabled(me, s, list);
        return new SpaceFields(service.enabled(s), service.configs(s));
    }

    @PutMapping("/spaces/{key}/fields/configs")
    @Transactional
    SpaceFields setConfigs(@AuthenticationPrincipal AuthUser me, @PathVariable String key, @RequestBody List<FieldService.Config> list) {
        Space s = space(me, key);
        service.setConfigs(me, s, list);
        return new SpaceFields(service.enabled(s), service.configs(s));
    }

    /** Form of a ticket of this type: visible fields in order, required ones, custom field defaults. */
    @GetMapping("/spaces/{key}/layout")
    @Transactional(readOnly = true)
    List<FieldService.LayoutField> layout(@AuthenticationPrincipal AuthUser me, @PathVariable String key, @RequestParam(required = false) UUID typeId) {
        Space s = space(me, key);
        TicketType type = s.getTicketTypes().stream().filter(t -> typeId == null ? t.getHierarchyLevel() == 0 : t.getId().equals(typeId))
                .min(Comparator.comparing(TicketType::getName)).orElseThrow(() -> Errors.badRequest("TYPE_REQUIRED", "Type non disponible dans l'espace"));
        return service.layout(s, type);
    }

    private Space space(AuthUser me, String key) {
        Space s = spaces.findByKey(key).orElseThrow(() -> Errors.notFound("Espace introuvable"));
        permissions.check(me, s, Perm.BROWSE);
        return s;
    }
}
