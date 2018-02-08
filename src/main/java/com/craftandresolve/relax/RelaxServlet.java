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
import com.craftandresolve.relax.annotation.entity.Format;
import com.craftandresolve.relax.annotation.parameter.Body;
import com.craftandresolve.relax.annotation.parameter.Header;
import com.craftandresolve.relax.annotation.parameter.Path;
import com.craftandresolve.relax.annotation.parameter.Query;
import com.craftandresolve.relax.annotation.service.Service;
import com.craftandresolve.relax.exception.HTTPCodeException;
import com.craftandresolve.relax.exception.InternalErrorException;
import com.craftandresolve.relax.exception.NotFoundException;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.*;


public class  RelaxServlet extends HttpServlet {

    private class QueryArgument {
        String name;
        String type;
        String format;
    }

    private class HeaderArgument {
        String header;
        String format;
    }

    private class PathArgument {
        String name;
        String type;
        String format;
    }

    private class DirectoryEntity {
        String type;
        String format;
        DirectoryEntity ofType;
        Map<String, DirectoryEntity> properties;
        String debug;
    }

    private class DirectoryEndpoint {
        String method;
        String path;
        List<HeaderArgument> headers;
        List<PathArgument> pathArguments;
        List<QueryArgument> queryArguments;
        DirectoryEntity request;
        DirectoryEntity response;
    }

    private class DirectoryService {
        String root;
        List<DirectoryEndpoint> endpoints;
    }

    private class DirectoryResponse {
        List<DirectoryService> services;
    }

    //

    private class ErrorResponse {
        Integer code;
        String shortText;
        String longText;
    }

    //

    private Gson gson;
    private final Map<String, Object> endpointContainers = new HashMap<>();
    private final Map<String, Method> endpoints = new HashMap<>();
    private String directory;
    private String directoryJson;

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

    @SuppressWarnings("unchecked")
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
                parameters.add(context.getRequest());
            }
            else if(parameterClass == HttpServletResponse.class) {
                parameters.add(context.getResponse());
            }
            else {
                for(Annotation annotation : annotations[i]) {

                    Class<?> annotationClass = annotation.annotationType();

                    if(annotationClass == Body.class) {
                        parameters.add(gson.fromJson(context.getRequest().getReader(), parameterTypes[i]));
                    }
                    else if(annotationClass == Header.class) {
                        parameters.add(((HttpServletRequest)context.getRequest()).getHeader(method.getParameters()[i].getAnnotation(Header.class).key()));
                    }
                    else if(annotationClass == Path.class) {
                        parameters.add(castPrimitive(pathValues.get(method.getParameters()[i].getAnnotation(Path.class).key()), parameterClass));
                    }
                    else if(annotationClass == Query.class) {
                        parameters.add(castPrimitive(context.getRequest().getParameter(method.getParameters()[i].getAnnotation(Query.class).key()), parameterClass));
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

    private void initialize(String directoryRoot, String endpointServices, boolean prettyJson) throws ServletException {

        directory = directoryRoot;

        GsonBuilder gsonBuilder = new GsonBuilder();
        if(prettyJson) {
            gsonBuilder.setPrettyPrinting();
        }
        gson = gsonBuilder.create();

        if(null == endpointServices) {
            throw new ServletException("No services init parameter found");
        }

        DirectoryResponse directoryResponse = null;

        for(String endpointClassString : endpointServices.split(",")) {
            try {
                Class<?> clazz = Class.forName(endpointClassString);

                Service endpointServiceAnnotation = clazz.getAnnotation(Service.class);
                if(null != endpointServiceAnnotation) {
                    String root = endpointServiceAnnotation.root();
                    String version = endpointServiceAnnotation.version();

                    if(null != directory) {
                        directoryResponse = new DirectoryResponse();
                        directoryResponse.services = new ArrayList<>();
                    }

                    String prefix = root + (!version.isEmpty() ? ("/" + version) : "");

                    DirectoryService directoryService = null;
                    if(null != directory) {
                        directoryService = new DirectoryService();
                        directoryService.root = prefix;
                        directoryResponse.services.add(directoryService);
                    }

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
                                    httpPath = ann.path();
                                } else if ("POST".equals(httpVerb)) {
                                    POST ann = method.getAnnotation(POST.class);
                                    httpPath = ann.path();
                                } else if ("PUT".equals(httpVerb)) {
                                    PUT ann = method.getAnnotation(PUT.class);
                                    httpPath = ann.path();
                                } else if ("HEAD".equals(httpVerb)) {
                                    HEAD ann = method.getAnnotation(HEAD.class);
                                    httpPath = ann.path();
                                } else if ("OPTIONS".equals(httpVerb)) {
                                    OPTIONS ann = method.getAnnotation(OPTIONS.class);
                                    httpPath = ann.path();
                                } else if ("DELETE".equals(httpVerb)) {
                                    DELETE ann = method.getAnnotation(DELETE.class);
                                    httpPath = ann.path();
                                }
                            }
                        }

                        if (null != httpVerb && null != httpPath) {
                            if(isValidPattern(httpPath)) {
                                if(null != directoryService) {
                                    if (null == directoryService.endpoints) {
                                        directoryService.endpoints = new ArrayList<>();
                                    }
                                    DirectoryEndpoint directoryEndpoint;
                                    directoryEndpoint = new DirectoryEndpoint();
                                    directoryEndpoint.method = httpVerb;
                                    directoryEndpoint.path = prefix + httpPath;
                                    directoryService.endpoints.add(directoryEndpoint);

                                    Class returnClass = (Class) ((ParameterizedType) method.getGenericReturnType()).getActualTypeArguments()[0];
                                    directoryEndpoint.response = classToEntity(returnClass, null);

                                    Annotation[][] anns = method.getParameterAnnotations();
                                    Class<?>[] parameterTypes = method.getParameterTypes();

                                    for (int i = 0; i < parameterTypes.length; ++i) {
                                        Class<?> parameterClass = parameterTypes[i];

                                        for (Annotation annotation : anns[i]) {

                                            Class<?> annotationClass = annotation.annotationType();

                                            if (annotationClass == Body.class) {
                                                directoryEndpoint.request = classToEntity(annotationClass, null);
                                            }
                                            else if (annotationClass == Header.class) {
                                                Header header = method.getParameters()[i].getAnnotation(Header.class);
                                                if(null == directoryEndpoint.headers) {
                                                    directoryEndpoint.headers = new ArrayList<>();
                                                }
                                                HeaderArgument argument = new HeaderArgument();
                                                argument.header = header.key();
                                                argument.format = header.format();
                                                directoryEndpoint.headers.add(argument);
                                            }
                                            else if (annotationClass == Path.class) {
                                                Path path = method.getParameters()[i].getAnnotation(Path.class);
                                                if(null == directoryEndpoint.pathArguments) {
                                                    directoryEndpoint.pathArguments = new ArrayList<>();
                                                }
                                                PathArgument argument = new PathArgument();
                                                argument.name = path.key();
                                                argument.format = path.format();
                                                argument.type = parameterClass.getCanonicalName();
                                                directoryEndpoint.pathArguments.add(argument);
                                            }
                                            else if (annotationClass == Query.class) {
                                                Query query = method.getParameters()[i].getAnnotation(Query.class);
                                                if(null == directoryEndpoint.queryArguments) {
                                                    directoryEndpoint.queryArguments = new ArrayList<>();
                                                }
                                                QueryArgument argument = new QueryArgument();
                                                argument.name = query.key();
                                                argument.format = query.format();
                                                argument.type = parameterClass.getCanonicalName();
                                                directoryEndpoint.queryArguments.add(argument);
                                            }
                                        }
                                    }
                                }

                                addEndpoint(httpVerb, prefix + httpPath, clazz.getConstructors()[0].newInstance(), method);
                            }
                            else {
                                throw new ServletException("Invalid endpoint pattern: " + httpPath);
                            }
                        }
                    }
                }
            }
            catch (Throwable e) {
                throw new ServletException(e);
            }
        }

        if (null != directoryResponse) {
            directoryJson = gson.toJson(directoryResponse, DirectoryResponse.class);
        }
    }

    private boolean isList(Class<?> clazz) {
        // for now, only List supported
        return List.class == clazz;
    }

    private boolean isMap(Class<?> clazz) {
        // for now, only Map supported
        return Map.class == clazz;
    }

    private DirectoryEntity classToEntity(Class<?> classToConvert, String format) {
        if (classToConvert == Void.class) {
            return null;
        }

        DirectoryEntity entity = new DirectoryEntity();

        entity.format = format;

        String className = classToConvert.getCanonicalName();

        if (classToConvert.isPrimitive() || className.startsWith("java.lang.")) {

            entity.type = classToConvert.getCanonicalName();

        } else {

            if (isList(classToConvert)) {

                entity.ofType = classToEntity(classToConvert.getTypeParameters()[0].getClass(), null);

                entity.debug = "isList";

            } else if (isMap(classToConvert)) {

                entity.ofType = classToEntity(classToConvert.getTypeParameters()[1].getClass(), null);

                entity.debug = "isMap";

            } else {

                Field[] fields = classToConvert.getDeclaredFields();

                if (fields.length > 0) {

                    entity.properties = new HashMap<>();

                    for (Field field : fields) {
                        Format formatAnnotation = field.getAnnotation(Format.class);
                        String fieldFormat = null;
                        if (null != formatAnnotation) {
                            fieldFormat = formatAnnotation.value();
                        }
                        entity.properties.put(field.getName(), classToEntity(field.getType(), fieldFormat));
                    }
                }
            }
        }

        return entity;
    }

    private void processRequest(HttpServletRequest req) throws ServletException, IOException {

        final AsyncContext context = req.startAsync();

        findAndInvokeEndpoint(context)
                .subscribeOn(Schedulers.io())
                .subscribe(new Subscriber<Object>() {

            private boolean emitted = false;

            @Override
            public void onCompleted() {
                if (!emitted) {
                    HttpServletResponse response = (HttpServletResponse) context.getResponse();
                    response.setStatus(HttpServletResponse.SC_NO_CONTENT);
                }
                context.complete();
                unsubscribe();
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
                if (null != o && !(o instanceof Void)) {
                    try {
                        response.addHeader("Content-Type", "application/json");
                        context.getResponse().getWriter().print(gson.toJson(o, o.getClass()));
                        emitted = true;
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        });
    }

    // HttpServlet

    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);
        initialize(config);
    }

    private void initialize(ServletConfig config) throws ServletException {
        initialize(config.getInitParameter("directory"), config.getInitParameter("services"), "true".equals(getInitParameter("prettyjson")));
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        if(null != directory && directory.equals(req.getPathInfo())) {
            resp.getWriter().print(directoryJson);
            return;
        }
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
