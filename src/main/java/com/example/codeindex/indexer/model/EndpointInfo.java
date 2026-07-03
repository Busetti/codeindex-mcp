package com.example.codeindex.indexer.model;

/** An HTTP entry point derived from a {@code @RequestMapping}-family annotation on a handler. */
public record EndpointInfo(
        String httpMethod,    // GET | POST | PUT | DELETE | PATCH | ANY
        String path,          // class-level path + method-level path joined
        String handlerFqn,    // handler method FQN
        String controllerFqn
) {
}
