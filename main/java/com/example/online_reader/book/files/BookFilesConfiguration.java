package com.example.online_reader.book.files;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods=false)
@EnableScheduling
public class BookFilesConfiguration implements org.springframework.web.servlet.config.annotation.WebMvcConfigurer {
    @Override public void addInterceptors(org.springframework.web.servlet.config.annotation.InterceptorRegistry registry) {
        registry.addInterceptor(new org.springframework.web.servlet.HandlerInterceptor() {
            @Override public void postHandle(jakarta.servlet.http.HttpServletRequest request,jakarta.servlet.http.HttpServletResponse response,Object handler,org.springframework.web.servlet.ModelAndView view) {
                if(view!=null && view.getStatus()!=null && view.getStatus().value()==503)response.setHeader("Retry-After","10");
            }
        }).addPathPatterns("/library/upload");
    }
    @org.springframework.context.annotation.Bean("bookUploadWorkers")
    @org.springframework.context.annotation.DependsOn("privateBookStorage")
    org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor workers() {
        var executor=new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);executor.setMaxPoolSize(2);executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("book-upload-");executor.setWaitForTasksToCompleteOnShutdown(true);executor.setAwaitTerminationSeconds(5);
        return executor;
    }
}
