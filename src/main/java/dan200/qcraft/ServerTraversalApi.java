import io.javalin.Javalin;

// https://javalin.io/documentation#wsbefore
public class ServerTraversalApi {

    public static class ServerTraversalApiConfigs {
        public static int getPort(){
            // TODO: get from config file or environment variable
            return 7070;
        }
        
        public static List<String> getLocalStargateAddresses() {
            // TODO: get from config file or environment variable
            return {
                "p3xgg75ag",
                "p3xgg77ag",
                "p3xaa77ag",
            };
        }

        public static Dictionary<String, String> getServerAddressMap() {
            // TODO: get from config file or environment variable
            return new Dictionary<String, String>() {
                {
                    put("ag", "123.456.789");
                    put("sg", "223.456.789");
                    put("mg", "323.456.789");
                }
            };
        }
        
        public static String getCurrentServerAddressSymbols() {
            // TODO: get from config file or environment variable
            return "ag";
        }

        public static List<String> getWhitelistedServers() {
            return getServerAddressMap().values();
        }
    }

    public static void onStargateDial(Javalin app) {
        app.ws("/stargate/dial", ws -> {
            ws.onConnect(ctx -> {})
        });
    }

    public static void main(String[] args) {
        initRestApi();
    }

    public static String getServerSymbols(String stargateAddress){
        return stargateAddress.substring(stargateAddress.length() - 2);
    }

    public static boolean shutdownGate(String stargateAddress);
    public static boolean dialGate(String stargateAddress);
    public static String serializePlayerInventoryToJson();
    public static void deletePlayerInventory();
    public static void tellClientToTeleport();
    public static boolean dialLocalGate(String stargateAddress);
    
    
    Request.Response getRequest(String url) throws IOException {
        return new OkHttpClient()
            .newCall(new Request.Builder()
                .url(url)
                .build()
            ).execute();
    }

    Request.Response postRequest(String url, String json) throws IOException {
        public static final MediaType JSON
            = MediaType.get("application/json; charset=utf-8");

        OkHttpClient client = new OkHttpClient();
        RequestBody body = RequestBody.create(json, JSON);
        Request request = new Request.Builder()
            .url(url)
            .post(body)
            .build();
        try (Response response = client.newCall(request).execute()) {
            return response;
        }
    }

    public static boolean dialRemoteGate(String stargateAddress) {
        String serverSymbols = getServerSymbols(stargateAddress);
        String remoteServerIp = ServerTraversalApiConfigs.getServerAddressMap().get(serverSymbols);
        // TODO: Put the serverIp and stargateAddress through a sanetizer to make sure there are no injection attacks
        String url = "http://" + remoteServerIp + "/stargate/" + stargateAddress + "/dial";
        var response = postRequest(url, "{}");
        return response.isSuccessful();
    }

    public static void onLocalsStargateDial(String stargateAddress){
        String serverSymbols = getServerSymbols(stargateAddress);
        if(ServerTraversalApiConfigs.getCurrentServerAddressSymbols() == serverSymbols){
            // Normal sgcraft dial
            dialLocalGate(stargateAddress);
        } else {
            if(dialRemoteGate(stargateAddress)){
                // Start gate animation
            } else {
                // Play fail sound
            }
        }
    }

    public static void onPlayerEnterRemotelyDialedGate(){
        // pack luggage
        String luggageAsJson = serializePlayerInventoryToJson();
        String url = "http://" + remoteServerIp + "/stargate/" + stargateAddress + "/dial";
        var response = postRequest(url, luggageAsJson)
        if(response.isSuccessful()) {
            deletePlayerInventory();
            tellClientToTeleport();
        } else {
            // Play fail sound
        }
    }

    public static void initRestApi() {
        Javalin app = Javalin.create(config -> {
            config.accessManager((handler, ctx, routeRoles) -> {
                if (ServerTraversalApiConfigs.getWhitelistedServers().contains(ctx.ip())) {
                    handler.handle(ctx);
                } else {
                    ctx.status(401).result("Unauthorized");
                }
            });
        }).start(ServerTraversalApiConfigs.getPort());

        app.post("/stargates/{stargateAddress}/playerLuggage", ctx -> {
            // This is the receiving end. The body of the request contains the player inventory.
            // This function should use the same logic as the verify and unpack luggage functions.
        });

        app.post("/stargates/{stargateAddress}/dial", ctx -> {
            String stargateAddress = ctx.pathParam("stargateAddress");
            if(ServerTraversalApiConfigs.getLocalStargateAddresses().contains(stargateAddress)) {
                if(dialGate(stargateAddress)){
                    ctx.result("OK");
                } else {
                    // 500 server error
                    ctx.status(500).result("Stargate exists but could not be dialed");
                }
            } else {
                // 404 Not found
                ctx.status(404).result("Gate does not exist");
            }
        });

        app.post("/stargates/{stargateAddress}/shutdown", ctx -> {
            String stargateAddress = ctx.pathParam("stargateAddress");
            if(ServerTraversalApiConfigs.getLocalStargateAddresses().contains(stargateAddress)) {
                if(shutdownGate(stargateAddress)){
                    ctx.result("OK");
                } else {
                    // 500 server error
                    ctx.status(500).result("Stargate exists but could not be shut down");
                }
            } else {
                // 404 Not found
                ctx.status(404).result("Gate does not exist");
            }
        });
    }
}
