/*
 * Copyright (C) 2016-2018 Craft+Resolve, LLC.
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
import com.craftandresolve.relax.type.CorsPreflightResponse;
import com.craftandresolve.relax.type.EmptyResponse;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.reactivex.Single;
import io.reactivex.SingleObserver;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

import javax.servlet.AsyncContext;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.annotation.Annotation;
import java.lang.reflect.*;
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
    }

    private class DirectoryEndpoint {
        String method;
        String path;
        String description;
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

    private String corsOrigins;
    private String corsLifetime;
    private final Map<String, Set<String>> corsMethods = new HashMap<>();
    private final Map<String, Set<String>> corsHeaders = new HashMap<>();

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
        if (pattern.isEmpty() || pattern.equals("/")) {
            return true;
        } else if(!pattern.startsWith("/")) {
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

    private Single<?> invokeEndpoint(AsyncContext context, Object container, Method method, String pattern) throws IllegalArgumentException, IllegalAccessException, InvocationTargetException, IOException {

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

        return ((Single<?>)method.invoke(container, parameters.toArray()));
    }

    private Single<?> findAndInvokeEndpoint(AsyncContext context) {

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
                    return Single.error(new InternalErrorException("illegal-access", e));
                }
                catch (InvocationTargetException e) {
                    return Single.error(new InternalErrorException("invocation-target", e));
                }
                catch (IllegalArgumentException e) {
                    return Single.error(new InternalErrorException("error-bad-argument-type", e));
                }
                catch (IOException e) {
                    return Single.error(new InternalErrorException("error-reading-body", e));
                }
            }
        }

        if (null != corsOrigins && "OPTIONS".equals(request.getMethod())) {
            return Single.just(new CorsPreflightResponse());
        }

        return Single.error(new NotFoundException("endpoint-not-found", "No endpoint was found."));
    }

    protected void initialize(
            String directoryRoot,
            Map<String, List<Class<?>>> endpointClasses,
            String corsOrigins,
            String corsLifetime,
            boolean prettyJson) throws ServletException {
        directory = directoryRoot;

        GsonBuilder gsonBuilder = new GsonBuilder();
        if(prettyJson) {
            gsonBuilder.setPrettyPrinting();
        }
        gson = gsonBuilder.create();

        this.corsOrigins = corsOrigins;
        this.corsLifetime = corsLifetime;

        if(null == endpointClasses || endpointClasses.isEmpty()) {
            return;
        }

        DirectoryResponse directoryResponse = null;

        for(Map.Entry<String, List<Class<?>>> entry : endpointClasses.entrySet()) {
            String baseDir = entry.getKey();

            for (Class<?> clazz : entry.getValue()) {
                try {
                    Service endpointServiceAnnotation = clazz.getAnnotation(Service.class);
                    if (null != endpointServiceAnnotation) {
                        String root = baseDir + endpointServiceAnnotation.root();
                        String version = endpointServiceAnnotation.version();

                        if (null != directory && null == directoryResponse) {
                            directoryResponse = new DirectoryResponse();
                            directoryResponse.services = new ArrayList<>();
                        }

                        String prefix = root + (!version.isEmpty() ? ("/" + version) : "");

                        DirectoryService directoryService = null;
                        if (null != directory) {
                            directoryService = new DirectoryService();
                            directoryService.root = prefix;
                            directoryResponse.services.add(directoryService);
                        }

                        for (Method method : clazz.getMethods()) {

                            String httpVerb = null;
                            String httpPath = null;
                            String endpointDescription = null;

                            Annotation[] annotations = method.getAnnotations();
                            for (Annotation annotation : annotations) {
                                String name = annotation.annotationType().getCanonicalName();
                                if (name.startsWith("com.craftandresolve.relax.annotation.endpoint.")) {
                                    httpVerb = name.replace("com.craftandresolve.relax.annotation.endpoint.", "");
                                    if ("GET".equals(httpVerb)) {
                                        GET ann = method.getAnnotation(GET.class);
                                        httpPath = ann.path();
                                        endpointDescription = ann.description();
                                    } else if ("POST".equals(httpVerb)) {
                                        POST ann = method.getAnnotation(POST.class);
                                        httpPath = ann.path();
                                        endpointDescription = ann.description();
                                    } else if ("PUT".equals(httpVerb)) {
                                        PUT ann = method.getAnnotation(PUT.class);
                                        httpPath = ann.path();
                                        endpointDescription = ann.description();
                                    } else if ("HEAD".equals(httpVerb)) {
                                        HEAD ann = method.getAnnotation(HEAD.class);
                                        httpPath = ann.path();
                                        endpointDescription = ann.description();
                                    } else if ("OPTIONS".equals(httpVerb)) {
                                        OPTIONS ann = method.getAnnotation(OPTIONS.class);
                                        httpPath = ann.path();
                                        endpointDescription = ann.description();
                                    } else if ("DELETE".equals(httpVerb)) {
                                        DELETE ann = method.getAnnotation(DELETE.class);
                                        httpPath = ann.path();
                                        endpointDescription = ann.description();
                                    }
                                }
                            }

                            if (null != httpVerb && null != httpPath) {

                                if (httpPath.equals("/")) {
                                    httpPath = "";
                                }

                                if (null != corsOrigins && !"OPTIONS".equals(httpVerb)) {
                                    Set<String> methods = corsMethods.computeIfAbsent(prefix + httpPath, k -> new HashSet<>());
                                    methods.add(httpVerb);
                                    Set<String> headers = corsHeaders.computeIfAbsent(httpVerb + "|" + prefix + httpPath, k -> new HashSet<>());
                                    headers.add("Content-Type");
                                }

                                if (isValidPattern(httpPath)) {
                                    if (null != directoryService) {
                                        if (null == directoryService.endpoints) {
                                            directoryService.endpoints = new ArrayList<>();
                                        }
                                        DirectoryEndpoint directoryEndpoint;
                                        directoryEndpoint = new DirectoryEndpoint();
                                        directoryEndpoint.method = httpVerb;
                                        directoryEndpoint.path = prefix + httpPath;
                                        directoryEndpoint.description = endpointDescription;
                                        directoryService.endpoints.add(directoryEndpoint);

                                        Class returnClass = (Class) ((ParameterizedType) method.getGenericReturnType()).getActualTypeArguments()[0];
                                        directoryEndpoint.response = classToEntity(returnClass, null, null);

                                        Annotation[][] anns = method.getParameterAnnotations();
                                        Class<?>[] parameterTypes = method.getParameterTypes();

                                        for (int i = 0; i < parameterTypes.length; ++i) {
                                            Class<?> parameterClass = parameterTypes[i];

                                            for (Annotation annotation : anns[i]) {

                                                Class<?> annotationClass = annotation.annotationType();

                                                if (annotationClass == Body.class) {
                                                    directoryEndpoint.request = classToEntity(annotationClass, null, null);
                                                } else if (annotationClass == Header.class) {
                                                    Header header = method.getParameters()[i].getAnnotation(Header.class);
                                                    if (null == directoryEndpoint.headers) {
                                                        directoryEndpoint.headers = new ArrayList<>();
                                                    }
                                                    HeaderArgument argument = new HeaderArgument();
                                                    argument.header = header.key();
                                                    argument.format = header.format();
                                                    directoryEndpoint.headers.add(argument);

                                                    if (null != corsOrigins && !"OPTIONS".equals(httpVerb)) {
                                                        Set<String> headers = corsHeaders.get(httpVerb + "|" + prefix + httpPath);
                                                        headers.add(header.key());
                                                    }
                                                } else if (annotationClass == Path.class) {
                                                    Path path = method.getParameters()[i].getAnnotation(Path.class);
                                                    if (null == directoryEndpoint.pathArguments) {
                                                        directoryEndpoint.pathArguments = new ArrayList<>();
                                                    }
                                                    PathArgument argument = new PathArgument();
                                                    argument.name = path.key();
                                                    argument.format = path.format();
                                                    argument.type = parameterClass.getCanonicalName();
                                                    directoryEndpoint.pathArguments.add(argument);
                                                } else if (annotationClass == Query.class) {
                                                    Query query = method.getParameters()[i].getAnnotation(Query.class);
                                                    if (null == directoryEndpoint.queryArguments) {
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
                                } else {
                                    throw new ServletException("Invalid endpoint pattern: " + httpPath);
                                }
                            }
                        }
                    }
                } catch (Throwable e) {
                    throw new ServletException(e);
                }
            }
        }

        if (null != directoryResponse) {
            directoryJson = gson.toJson(directoryResponse, DirectoryResponse.class);
        }
    }

    protected void initialize(
            String directoryRoot,
            List<Class<?>> endpointClasses,
            String corsOrigins,
            String corsLifetime,
            boolean prettyJson) throws ServletException {
        Map<String, List<Class<?>>> endpointMap = new HashMap<>();
        endpointMap.put("", endpointClasses);
        initialize(directoryRoot, endpointMap, corsOrigins, corsLifetime, prettyJson);
    }

    private void initialize(String directoryRoot, String endpointServices, String corsOrigins, String corsLifecycle, boolean prettyJson) throws ServletException {
        List<Class<?>> endpointClasses = new ArrayList<>();
        for(String endpointClassString : endpointServices.split(",")) {
            try {
                endpointClasses.add(Class.forName(endpointClassString.trim()));
            }
            catch (Throwable e) {
                throw new ServletException(e);
            }
        }
        initialize(directoryRoot, endpointClasses, corsOrigins, corsLifecycle, prettyJson);
    }

    private boolean isList(Class<?> clazz) {
        // for now, only List supported
        return List.class == clazz;
    }

    private boolean isMap(Class<?> clazz) {
        // for now, only Map supported
        return Map.class == clazz;
    }

    private DirectoryEntity classToEntity(Class<?> classToConvert, String format, Class<?> containedType) {
        if (classToConvert == Void.class) {
            return null;
        }

        DirectoryEntity entity = new DirectoryEntity();

        entity.format = format;

        String className = classToConvert.getCanonicalName();

        if (classToConvert.isPrimitive() || className.startsWith("java.lang.")) {

            entity.type = classToConvert.getCanonicalName();

        } else {

            if (isList(classToConvert) || isMap(classToConvert)) {

                entity.type = classToConvert.getCanonicalName();

                entity.ofType = classToEntity(containedType, null, null);

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

                        Class<?> ofType = null;
                        if (isMap(field.getType())) {
                            Type type = field.getGenericType();
                            if (type instanceof ParameterizedType) {
                                ParameterizedType pt = (ParameterizedType) type;
                                if (null != pt.getActualTypeArguments() && pt.getActualTypeArguments().length == 2) {
                                    ofType = ((Class) pt.getActualTypeArguments()[1]);
                                }
                            }
                        } else if (isList(field.getType())) {
                            Type type = field.getGenericType();
                            if (type instanceof ParameterizedType) {
                                ParameterizedType pt = (ParameterizedType) type;
                                if (null != pt.getActualTypeArguments() && pt.getActualTypeArguments().length == 1) {
                                    ofType = ((Class) pt.getActualTypeArguments()[0]);
                                }
                            }
                        }

                        entity.properties.put(field.getName(), classToEntity(field.getType(), fieldFormat, ofType));
                    }
                }
            }
        }

        return entity;
    }

    private void processRequest(HttpServletRequest req) {

        final AsyncContext context = req.startAsync();

        findAndInvokeEndpoint(context)
                .subscribeOn(Schedulers.io())
                .subscribe(new SingleObserver<Object>() {

                    private Disposable disposable;

                    @Override
                    public void onSubscribe(Disposable d) {
                        disposable = d;
                    }

                    @Override
                    public void onSuccess(Object o) {
                        HttpServletResponse response = (HttpServletResponse) context.getResponse();
                        if (o instanceof EmptyResponse) {
                            response.setStatus(HttpServletResponse.SC_NO_CONTENT);

                            sendCorsHeaders(req, response);
                        } else {
                            if (o instanceof CorsPreflightResponse) {

                                response.addHeader("Access-Control-Allow-Origin", corsOrigins);
                                String pathInfo = req.getPathInfo();
                                Set<String> methods = corsMethods.get(pathInfo);
                                if (null != methods) {
                                    response.addHeader("Access-Control-Allow-Methods", String.join(",", methods));
                                    Set<String> allHeaders = new HashSet<>();
                                    for (String method: methods) {
                                        Set<String> headers = corsHeaders.get(method + "|" + req.getPathInfo());
                                        if (null != headers) {
                                            allHeaders.addAll(headers);
                                        }
                                    }
                                    if (!allHeaders.isEmpty()) {
                                        response.addHeader("Access-Control-Allow-Headers", String.join(",", allHeaders));
                                    }
                                }
                                if (null != corsLifetime) {
                                    response.addHeader("Access-Control-Max-Age", corsLifetime);
                                }
                            } else {

                                try {
                                    sendCorsHeaders(req, response);

                                    response.addHeader("Content-Type", "application/json");
                                    context.getResponse().getWriter().print(gson.toJson(o, o.getClass()));
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }
                            }
                        }
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        HttpServletResponse response = (HttpServletResponse) context.getResponse();

                        sendCorsHeaders(req, response);

                        ErrorResponse errorResponse = new ErrorResponse();
                        if(throwable instanceof HTTPCodeException) {
                            errorResponse.code = ((HTTPCodeException) throwable).getCode();
                            errorResponse.shortText = ((HTTPCodeException) throwable).getShortText();
                        }
                        else {
                            errorResponse.code = 500;
                        }

                        if (!(throwable instanceof HTTPCodeException)) {
                            StringWriter writer = new StringWriter();
                            PrintWriter printWriter = new PrintWriter(writer);
                            throwable.printStackTrace(printWriter);
                            Throwable causedBy = throwable.getCause();
                            while (null != causedBy) {
                                writer.append("\n\nCaused By\n\n");
                                causedBy.printStackTrace(printWriter);
                                causedBy = causedBy.getCause();
                            }
                            errorResponse.longText = writer.toString().replace("\\n\\t", "\n\t");
                        }

                        response.setStatus(errorResponse.code);
                        response.addHeader("Content-Type", "application/json");
                        try {
                            context.getResponse().getWriter().print(gson.toJson(errorResponse));
                        }
                        catch (IOException e) {
                            // IGNORED
                        }
                        context.complete();
                        disposable.dispose();
                    }
                });
    }

    private void sendCorsHeaders(HttpServletRequest request, HttpServletResponse response) {
        if (null != corsOrigins) {
            Set<String> methods = corsMethods.get(request.getPathInfo());
            if (null != methods && methods.contains(request.getMethod())) {
                response.addHeader("Access-Control-Allow-Origin", corsOrigins);
                Set<String> headers = corsHeaders.get(request.getMethod() + "|" + request.getPathInfo());
                if (null != headers) {
                    response.addHeader("Access-Control-Allow-Headers", String.join(",", headers));
                }
                if (null != corsLifetime) {
                    response.addHeader("Access-Control-Max-Age", corsLifetime);
                }
            }
        }
    }

    // HttpServlet

    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);
        initialize(config);
    }

    private void initialize(ServletConfig config) throws ServletException {
        initialize(
                config.getInitParameter("directory"),
                config.getInitParameter("services"),
                config.getInitParameter("corsorigins"),
                config.getInitParameter("corslifetime"),
                "true".equals(getInitParameter("prettyjson")));
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if(null != directory && directory.equals(req.getPathInfo())) {
            resp.getWriter().print(directoryJson);
            return;
        }
        processRequest(req);
    }

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp) {
        processRequest(req);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) {
        processRequest(req);
    }

    @Override
    protected void doHead(HttpServletRequest req, HttpServletResponse resp) {
        processRequest(req);
    }

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) {
        processRequest(req);
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) {
        processRequest(req);
    }
}
