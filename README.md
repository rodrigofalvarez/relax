Relax - A Java library for annotation-routed, RxJava-enabled endpoints
======================================================================

Endpoint Example:
----------------

The endpoint looks like this:

```java
@Service(root = "/myservice", version = "v2")
public class MyService {

    @GET("/person")
    public Observable<PersonList> listPersons(@Header("Authorization") String authorization,
                                              @Query("offset") Integer offset,
                                              @Query("limit") Integer limit) {
        ...
    }

    @GET("/person/{id}")
    public Observable<Person> getPerson(@Path("id") Integer personId,
                                        @Header("Authorization") String authorization) {
        ...
    }

    @PUT("/person/{id}")
    public Observable<Person> putPerson(@Path("id") Integer personId,
                                        @Header("Authorization") String authorization,
                                        @Body Person person) {
        ...
    }
}

```

The web.xml file contains this:

```xml
<servlet>
    <servlet-name>RelaxServlet</servlet-name>
    <servlet-class>com.craftandresolve.relax.RelaxServlet</servlet-class>
    <async-supported>true</async-supported>
    <init-param>
        <param-name>endpoints</param-name>
        <param-value>com.craftandresolve.relax.example.MyService,com.craftandresolve.relax.example.MyOtherService</param-value>
    </init-param>
</servlet>
```

License
-------

    Copyright 2016 Craft+Resolve, LLC.
    
    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.



 [1]: http://rodrigofalvarez.github.com/relax/
 [2]: http://github.com/rodrigofalvarez/relax/downloads
