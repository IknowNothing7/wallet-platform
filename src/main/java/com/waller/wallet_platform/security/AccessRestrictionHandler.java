package com.waller.wallet_platform.security;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.SneakyThrows;

@Component 
public class AccessRestrictionHandler implements AccessDeniedHandler{

    @Override
    @SneakyThrows 
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException, ServletException {

                response.setStatus(HttpStatus.FORBIDDEN.value());
                response.getWriter().write("You have no permission to do that");
    }

}
