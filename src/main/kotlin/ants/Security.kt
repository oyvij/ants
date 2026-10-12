package ants

import org.operaton.bpm.engine.rest.security.auth.impl.ContainerBasedAuthenticationProvider
import org.operaton.bpm.webapp.impl.security.auth.ContainerBasedAuthenticationFilter
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import org.springframework.security.web.SecurityFilterChain

/** One user, `admin`, on every page, Cockpit and the REST API included. */
@Configuration
class Security {
    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain = http
        // A browser fetches the app's manifest and icons without the login cookie; the login page needs its stylesheet.
        .authorizeHttpRequests { it.requestMatchers("/manifest.webmanifest", "/icon-*.png", "/ants.css").permitAll().anyRequest().authenticated() }
        // Ants' own login page (login.kte); Log out, in the sidebar, lands back on it.
        .formLogin { it.loginPage("/login").failureUrl("/login?error").permitAll() }
        .logout { it.logoutSuccessUrl("/login?logout").permitAll() }
        // Cockpit sends its own CSRF token, checked by Operaton.
        .csrf { it.ignoringRequestMatchers("/operaton/**") }
        // The modeler opens the New Agent and New Skill forms in a frame.
        .headers { it.frameOptions { frame -> frame.sameOrigin() } }
        .build()

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
