/*
 * Copyright (C) 2016 Craft+Resolve, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.craftandresolve.relax;

import com.craftandresolve.relax.annotation.endpoint.*;
import com.craftandresolve.relax.annotation.parameter.Body;
import com.craftandresolve.relax.annotation.parameter.Header;
import com.craftandresolve.relax.annotation.parameter.Path;
import com.craftandresolve.relax.annotation.parameter.Query;
import com.craftandresolve.relax.annotation.service.Service;
import com.craftandresolve.relax.exception.HTTPCodeException;
import com.craftandresolve.relax.exception.InternalErrorException;
import com.craftandresolve.relax.exception.NotFoundException;
import com.google.gson.Gson;
import rx.Observable;
import rx.Subscriber;
import rx.schedulers.Schedulers;

import javax.servlet.AsyncContext;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;


public class RelaxServlet extends HttpServlet {

    private class ErrorResponse {
        public Integer code;
        public String shortText;
        public String longText;
    }

    //

    private final Gson gson = new Gson();
    private final Map<String, Object> endpointContainers = new HashMap<>();
    private final Map<String, Method> endpoints = new HashMap<>();

    //

    private String trimPath(String path) {
        if(path.length() > 1) {
            int start = 0;
            int end = path.length();
            if (path.charAt(start) == '/') {
                ++start;
            }
            if (path.charAt(end - 1) == '/') {
                --end;
            }
            return path.substring(start, end);
        }
        return path;
    }

    private boolean requestPathMatchesPattern(String requestPath, String pattern) {

        // trim verb
        String pathVerb = (requestPath.split("\\|"))[0];
        String patternVerb = (pattern.split("\\|"))[0];
        if(!pathVerb.equals(patternVerb)) {
            return false;
        }

        requestPath = requestPath.substring(requestPath.indexOf('|')+1, requestPath.length());
        pattern = pattern.substring(pattern.indexOf('|')+1, pattern.length());

        // trim ends
        requestPath = trimPath(requestPath);
        pattern = trimPath(pattern);

        // split
        String[] pathParts = requestPath.split("/");
        String[] patternParts = pattern.split("/");

        if(pathParts.length != patternParts.length) {
            return false;
        }

        for(int i=0; i<patternParts.length; ++i) {
            String pathPart = pathParts[i];
            String patternPart = patternParts[i];

            if(patternPart.charAt(0) != '{' && !pathPart.equals(patternPart)) {
                return false;
            }
        }

        return true;
    }

    private boolean isValidPattern(String pattern) {
        if(pattern.charAt(0) != '/') {
            return false;
        }

        pattern = pattern.substring(1, pattern.charAt(pattern.length()-1) == '/' ? pattern.length()-1 : pattern.length());

        Set<String> labels = new HashSet<>();
        for(String part : pattern.split("/")) {
            if(part.startsWith("{")) {
                if(part.endsWith("}")) {
                    String label = part.substring(1, part.length()-1);
                    if(!labels.contains(label)) {
                        labels.add(label);
                    }
                    else {
                        return false;
                    }
                }
                else {
                    return false;
                }
            }
            else if(part.isEmpty()) {
                return false;
            }
            else {
                for(int i=0; i<part.length(); ++i) {
                    char c = part.charAt(i);
                    if(!Character.isAlphabetic(c) && !Character.isDigit(c)) {
                        return false;
                    }
                }
            }
        }

        return true;
    }

    private void addEndpoint(String verb, String pattern, Object container, Method endpoint) {
        endpointContainers.put(verb + "|" + pattern, container);
        endpoints.put(verb + "|" + pattern, endpoint);
    }

    private Map<String, String> generatePathValues(AsyncContext context, String pattern) {
        Map<String, String> map = new HashMap<>();

        String[] patternParts = pattern.split("/");
        String[] pathParts = ((HttpServletRequest)context.getRequest()).getPathInfo().split("/");

        for(int i=0; i<patternParts.length; ++i) {
            String patternPart = patternParts[i];
            String pathPart = pathParts[i];

            if(patternPart.startsWith("{")) {
                String label = patternPart.substring(1, patternPart.length()-1);
                map.put(label, pathPart);
            }
        }

        return map;
    }

    private <T> T castPrimitive(String s, Class<T> clazz) {
        if(clazz == String.class) {
            return (T)s;
        }
        else if(clazz == Integer.class) {
            return (T)(Integer.valueOf(s));
        }
        else if(clazz == Character.class) {
            return (T)((Character)s.charAt(0));
        }
        else if(clazz == Short.class) {
            return (T)(Short.valueOf(s));
        }
        else if(clazz == Long.class) {
            return (T)(Long.valueOf(s));
        }
        else if(clazz == Float.class) {
            return (T)(Float.valueOf(s));
        }
        else if(clazz == Double.class) {
            return (T)(Double.valueOf(s));
        }
        else if(clazz == Boolean.class) {
            return (T)(Boolean.valueOf(s));
        }
        throw new IllegalArgumentException();
    }

    private Observable<?> invokeEndpoint(AsyncContext context, Object container, Method method, String pattern) throws IllegalArgumentException, IllegalAccessException, InvocationTargetException, IOException {

        Map<String, String> pathValues = generatePathValues(context, pattern);

        Annotation[][] annotations = method.getParameterAnnotations();
        Class<?>[] parameterTypes = method.getParameterTypes();

        List<Object> parameters = new ArrayList<>();
        for(int i = 0; i < parameterTypes.length; ++i) {
            Class<?> parameterClass = parameterTypes[i];

            if(parameterClass == HttpServletRequest.class) {
                parameters.add((HttpServletRequest)context.getRequest());
            }
            else if(parameterClass == HttpServletResponse.class) {
                parameters.add((HttpServletResponse)context.getResponse());
            }
            else {
                for(Annotation annotation : annotations[i]) {

                    Class<?> annotationClass = annotation.annotationType();

                    if(annotationClass == Body.class) {
                        parameters.add(gson.fromJson(((HttpServletRequest)context.getRequest()).getReader(), annotationClass));
                    }
                    else if(annotationClass == Header.class) {
                        parameters.add(((HttpServletRequest)context.getRequest()).getHeader(method.getParameters()[i].getAnnotation(Header.class).value()));
                    }
                    else if(annotationClass == Path.class) {
                        Path path = method.getParameters()[i].getAnnotation(Path.class);
                        String key = path.value();
                        String value = pathValues.get(key);
                        Object thing = castPrimitive(value, parameterClass);
                        parameters.add(castPrimitive(pathValues.get(method.getParameters()[i].getAnnotation(Path.class).value()), parameterClass));
                    }
                    else if(annotationClass == Query.class) {
                        parameters.add(castPrimitive(((HttpServletRequest)context.getRequest()).getParameter(method.getParameters()[i].getAnnotation(Query.class).value()), parameterClass));
                    }
                }
            }
        }

        return ((Observable<?>)method.invoke(container, parameters.toArray()));
    }

    private Observable<?> findAndInvokeEndpoint(AsyncContext context) {

        HttpServletRequest request = (HttpServletRequest)context.getRequest();
        String matchable = request.getMethod() + "|" + request.getPathInfo();

        for(String pattern : endpoints.keySet()) {
            if(requestPathMatchesPattern(matchable, pattern)) {
                Method method = endpoints.get(pattern);
                Object endpointContainer = endpointContainers.get(pattern);
                try {
                    return invokeEndpoint(context, endpointContainer, method, pattern);
                }
                catch (IllegalAccessException e) {
                    return Observable.error(new InternalErrorException("illegal-access", e));
                }
                catch (InvocationTargetException e) {
                    return Observable.error(new InternalErrorException("invocation-target", e));
                }
                catch (IllegalArgumentException e) {
                    return Observable.error(new InternalErrorException("error-bad-argument-type", e));
                }
                catch (IOException e) {
                    return Observable.error(new InternalErrorException("error-reading-body", e));
                }
            }
        }

        return Observable.error(new NotFoundException("endpoint-not-found", "No endpoint was found."));
    }

    private void initialize(String endpointString) throws ServletException {

        if(null == endpointString) {
            throw new ServletException("No endpoints init parameter found");
        }

        for(String endpointClassString : endpointString.split(",")) {
            try {
                Class<?> clazz = Class.forName(endpointClassString);

                Service endpointServiceAnnotation = clazz.getAnnotation(Service.class);
                if(null != endpointServiceAnnotation) {
                    String root = endpointServiceAnnotation.root();
                    String version = endpointServiceAnnotation.version();

                    String prefix = root + (!version.isEmpty() ? ("/" + version) : "");

                    for (Method method : clazz.getMethods()) {

                        String httpVerb = null;
                        String httpPath = null;

                        Annotation[] annotations = method.getAnnotations();
                        for (Annotation annotation : annotations) {
                            String name = annotation.annotationType().getCanonicalName();
                            if (name.startsWith("com.craftandresolve.relax.annotation.endpoint.")) {
                                httpVerb = name.replace("com.craftandresolve.relax.annotation.endpoint.", "");
                                if ("GET".equals(httpVerb)) {
                                    GET ann = method.getAnnotation(GET.class);
                                    httpPath = ann.value();
                                } else if ("POST".equals(httpVerb)) {
                                    POST ann = method.getAnnotation(POST.class);
                                    httpPath = ann.value();
                                } else if ("PUT".equals(httpVerb)) {
                                    PUT ann = method.getAnnotation(PUT.class);
                                    httpPath = ann.value();
                                } else if ("HEAD".equals(httpVerb)) {
                                    HEAD ann = method.getAnnotation(HEAD.class);
                                    httpPath = ann.value();
                                } else if ("OPTIONS".equals(httpVerb)) {
                                    OPTIONS ann = method.getAnnotation(OPTIONS.class);
                                    httpPath = ann.value();
                                } else if ("DELETE".equals(httpVerb)) {
                                    DELETE ann = method.getAnnotation(DELETE.class);
                                    httpPath = ann.value();
                                }
                            }
                        }

                        if (null != httpVerb && null != httpPath) {
                            if(isValidPattern(httpPath)) {
                                addEndpoint(httpVerb, prefix + httpPath, clazz.getConstructors()[0].newInstance(), method);
                            }
                            else {
                                throw new ServletException("Invalid endpoint pattern: " + httpPath);
                            }
                        }
                    }
                }

                if(endpoints.isEmpty()) {
                    throw new ServletException("No endpoints configured in endpoints init-param");
                }
            }
            catch (Throwable e) {
                throw new ServletException(e);
            }
        }
    }

    private void processRequest(HttpServletRequest req) throws ServletException, IOException {

        final AsyncContext context = req.startAsync();

        findAndInvokeEndpoint(context)
                .subscribeOn(Schedulers.io())
                .subscribe(new Subscriber<Object>() {
            @Override
            public void onCompleted() {
                // IGNORED
            }

            @Override
            public void onError(Throwable throwable) {
                HttpServletResponse response = (HttpServletResponse) context.getResponse();

                ErrorResponse errorResponse = new ErrorResponse();
                if(throwable instanceof HTTPCodeException) {
                    errorResponse.code = ((HTTPCodeException) throwable).getCode();
                    errorResponse.shortText = ((HTTPCodeException) throwable).getShortText();
                }
                else {
                    errorResponse.code = 500;
                }
                errorResponse.longText = throwable.getLocalizedMessage();

                response.setStatus(errorResponse.code);
                response.addHeader("Content-Type", "application/json");
                try {
                    context.getResponse().getWriter().print(gson.toJson(errorResponse));
                }
                catch (IOException e) {
                    // IGNORED
                }
                context.complete();
                unsubscribe();
            }

            @Override
            public void onNext(Object o) {

                HttpServletResponse response = (HttpServletResponse) context.getResponse();
                try {
                    response.addHeader("Content-Type", "application/json");
                    context.getResponse().getWriter().print(gson.toJson(o, o.getClass()));
                }
                catch (IOException e) {
                    throw new RuntimeException(e);
                }
                context.complete();
                unsubscribe();
            }
        });
    }

    // HttpServlet

    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);
        initialize(config.getInitParameter("endpoints"));
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        processRequest(req);
    }

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        processRequest(req);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        processRequest(req);
    }

    @Override
    protected void doHead(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        processRequest(req);
    }

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        processRequest(req);
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        processRequest(req);
    }
}
