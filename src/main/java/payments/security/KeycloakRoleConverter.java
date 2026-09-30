package payments.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class KeycloakRoleConverter
        implements Converter<Jwt, Collection<GrantedAuthority>> {

    private static final Set<String> SUPPORTED_ROLES =
            Set.of("merchant", "simulator");

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Object realmAccessClaim = jwt.getClaims().get("realm_access");

        if (!(realmAccessClaim instanceof Map<?, ?> realmAccess)) {
            return List.of();
        }

        Object rolesClaim = realmAccess.get("roles");

        if (!(rolesClaim instanceof Collection<?> roles)) {
            return List.of();
        }

        Set<GrantedAuthority> authorities = new LinkedHashSet<>();

        for (Object value : roles) {
            if (value instanceof String role
                    && SUPPORTED_ROLES.contains(role)) {
                authorities.add(
                        new SimpleGrantedAuthority("ROLE_" + role)
                );
            }
        }

        return List.copyOf(authorities);
    }
}