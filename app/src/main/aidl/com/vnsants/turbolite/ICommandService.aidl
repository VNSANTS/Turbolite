package com.vnsants.turbolite;

interface ICommandService {
    String execute(String command, long timeoutMillis) = 1;
    void destroy() = 16777114;
}
