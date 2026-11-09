package ants

import org.operaton.bpm.engine.rest.security.auth.impl.ContainerBasedAuthenticationProvider
import org.operaton.bpm.webapp.impl.security.auth.ContainerBasedAuthenticationFilter
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.AuthenticationSuccessHandler
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler
import java.nio.file.Path

/** One user, `admin`, on every page, Cockpit and the REST API included. */
@Configuration
class Security {
    @Bean
    fun secretStore(@Value("\${ants.home}") home: Path, settings: Settings) = SecretStore(home, settings.plainPassword())

    @Bean
    fun filterChain(http: HttpSecurity, store: SecretStore): SecurityFilterChain {
        val then = SavedRequestAwareAuthenticationSuccessHandler()
        // A login hands the typed password to the Safe, which it unlocks.
        val unlocking = AuthenticationSuccessHandler { request, response, authentication ->
            store.unlock(request.getParameter("password"))
            then.onAuthenticationSuccess(request, response, authentication)
        }
        return http
        // A browser fetches the app's manifest, icons and service worker without the login cookie; the login page needs its stylesheet and,
        // like every page, loads markdown.js and mermaid.js (which load nothing more where no field is markdown and no block a diagram).
        // Agents reach the MCP endpoint with their Task's bearer token, and a Script Task's Script reaches /posts with its
        // own, which each checks itself, never the login.
        .authorizeHttpRequests { it.requestMatchers("/manifest.webmanifest", "/sw.js", "/icon-*.png", "/ants.css", "/markdown.js", "/mermaid.js", "/mcp", "/posts").permitAll().anyRequest().authenticated() }
        // Ants' own login page (login.kte); Log out, in the sidebar, lands back on it.
        .formLogin { it.loginPage("/login").failureUrl("/login?error").successHandler(unlocking).permitAll() }
        .logout { it.logoutSuccessUrl("/login?logout").permitAll() }
        // Cockpit sends its own CSRF token, checked by Operaton.
        .csrf { it.ignoringRequestMatchers("/operaton/**", "/mcp", "/posts") }
        // The modeler opens the New Agent and New Skill forms in a frame.
        .headers { it.frameOptions { frame -> frame.sameOrigin() } }
        .build()
    }

    @Bean
    fun users(settings: Settings) = UserDetailsService { username ->
        if (username != "admin") throw UsernameNotFoundException(username)
        User.withUsername(username).password(settings.password()).build()
    }

    @Bean
    fun passwordEncoder() = PasswordEncoderFactories.createDelegatingPasswordEncoder()

    /** Signs Cockpit in as the user Ants' login let through, so there is one login. */
    @Bean
    fun cockpitLogin() = FilterRegistrationBean(ContainerBasedAuthenticationFilter()).apply {
        addInitParameter("authentication-provider", ContainerBasedAuthenticationProvider::class.java.name)
        addUrlPatterns("/operaton/*")
        order = 101 // after Spring Security's filter chain
    }
}
