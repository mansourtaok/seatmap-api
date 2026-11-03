package com.seatmap.api;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
class WebConfig implements WebMvcConfigurer {

	private final SeatmapProperties properties;

	WebConfig(SeatmapProperties properties) {
		this.properties = properties;
	}

	@Override
	public void addCorsMappings(CorsRegistry registry) {
		registry.addMapping("/api/**")
			.allowedOrigins(this.properties.corsAllowedOrigins().toArray(String[]::new))
			.allowedMethods("GET", "POST", "PUT", "DELETE")
			// Lets the session cookie through when the web app runs on another origin. The
			// Angular dev server should proxy /api instead, which keeps everything same-origin.
			.allowCredentials(true)
			.exposedHeaders("ETag", "Location", "Retry-After");
	}

}
