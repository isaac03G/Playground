package com.proyecto.aula.playgroundv2.config;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import jakarta.servlet.http.HttpServletRequest;

@ControllerAdvice(annotations = Controller.class)
public class CsrfTokenAdvice {

    @ModelAttribute
    public void prepareCsrfToken(HttpServletRequest request) {
        Object csrfToken = request.getAttribute(CsrfToken.class.getName());
        if (csrfToken instanceof CsrfToken token) {
            token.getToken();
        }
    }
}
