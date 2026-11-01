package com.example.online_reader.config;

import jakarta.servlet.DispatcherType;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.security.web.header.writers.CacheControlHeadersWriter;
import org.springframework.security.web.header.writers.ContentSecurityPolicyHeaderWriter;
import org.springframework.security.web.header.writers.HstsHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.XContentTypeOptionsHeaderWriter;
import org.springframework.security.web.header.writers.XXssProtectionHeaderWriter;
import org.springframework.security.web.header.writers.frameoptions.XFrameOptionsHeaderWriter;

import java.util.List;

/** One set of native writers before security: firewall rejections must have the same headers. */
@Configuration(proxyBeanMethods = false)
public class HttpHeadersConfiguration {
    @Bean
    FilterRegistrationBean<HeaderWriterFilter> securityHeaders(SecurityProperties security) {
        var hsts = new HstsHeaderWriter();
        hsts.setMaxAgeInSeconds(31536000);
        hsts.setIncludeSubDomains(false);
        hsts.setPreload(false);
        var filter = new HeaderWriterFilter(List.of(
                new CacheControlHeadersWriter(), new XContentTypeOptionsHeaderWriter(),
                new XFrameOptionsHeaderWriter(XFrameOptionsHeaderWriter.XFrameOptionsMode.DENY),
                new XXssProtectionHeaderWriter(), hsts,
                new ReferrerPolicyHeaderWriter(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER),
                new ContentSecurityPolicyHeaderWriter("default-src 'none'; script-src 'self'; style-src 'self'; "
                        + "img-src 'self'; font-src 'self'; connect-src 'self'; object-src 'none'; "
                        + "base-uri 'none'; form-action 'self'; frame-ancestors 'none'")));
        // Before FilterChainProxy, not merely its inner HeaderWriterFilter: the firewall runs first.
        filter.setShouldWriteHeadersEagerly(true);
        var registration = new FilterRegistrationBean<>(filter);
        registration.setName("securityHeaders");
        registration.setOrder(Math.subtractExact(security.getFilter().getOrder(), 1));
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
        return registration;
    }
}
