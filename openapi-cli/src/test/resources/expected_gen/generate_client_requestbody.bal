import  ballerina/http;

# refComponent
public isolated client class Client {
    final http:Client clientEp;
    # Client initialization.
    #
    # + clientConfig - Client configuration details
    # + serviceUrl - Connector server URL
    # + return -  An error at the failure of client initialization
    public isolated function init(http:ClientConfiguration clientConfig =  {}, string serviceUrl = "https") returns error? {
        http:Client httpEp = check new (serviceUrl, clientConfig);
        self.clientEp = httpEp;
    }
    # Creates a new user.
    #
    # + return - OK
    remote isolated function  requestBody(User payload) returns http:Response|error {
        string  path = string `/requestBody`;
        http:Request request = new;
        json jsonBody = check payload.cloneWithType(json);
        request.setPayload(jsonBody);
        http:Response response = check self.clientEp-> post(path, request, targetType=http:Response);
        return response;
    }
}
