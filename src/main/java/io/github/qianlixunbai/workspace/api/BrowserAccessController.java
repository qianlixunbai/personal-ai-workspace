package io.github.qianlixunbai.workspace.api;

import io.github.qianlixunbai.workspace.security.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/security")
public class BrowserAccessController {
    private final BrowserClients clients;
    public BrowserAccessController(BrowserClients clients) { this.clients = clients; }
    public record PairingRequest(@NotNull String origin, @NotNull String displayName, @AssertTrue boolean userApproved) {}
    public record ExchangeRequest(@NotNull UUID pairingId, @NotNull @Size(max=43) String pairingSecret) {}
    @PostMapping("/pairings")
    public BrowserClients.Pairing create(@Valid @RequestBody PairingRequest request) {
        return clients.create(request.origin(), request.displayName());
    }
    @PostMapping("/pairings/exchange")
    public BrowserClients.Exchange exchange(@Valid @RequestBody ExchangeRequest request, HttpServletRequest http) {
        return clients.exchange(request.pairingId(), request.pairingSecret(), http.getHeader("Origin"));
    }
    @GetMapping("/clients") public List<ClientIdentity> list() { return clients.list(); }
    @DeleteMapping("/clients/{id}") public ResponseEntity<Void> revoke(@PathVariable UUID id) {
        clients.revoke(id.toString()); return ResponseEntity.noContent().build();
    }
}
