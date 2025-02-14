import ballerina/http;

enum Action {
    GET,
    POST,
    PUT,
    DELETE,
    PATCH
}

type Link record {|
    string rel;
    Action actions?;
|};

service /payloadV on new http:Listener(9090) {

    # Represents Snowpeak reservation resource
    #
    # + link - Reservation representation
    resource function post reservation(@http:Payload Link link) {
    }
}
