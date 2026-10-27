package com.vnsants.turbolite;

interface ICommandService {
    String execute(String command, long timeoutMillis);
    void destroy() = 16777114;
}
