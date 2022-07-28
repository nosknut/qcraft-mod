package dan200.qcraft.client;

import cpw.mods.fml.client.FMLClientHandler;
import dan200.qcraft.shared.LostLuggage;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.resources.I18n;

import cpw.mods.fml.client.FMLClientHandler;
import cpw.mods.fml.client.registry.ISimpleBlockRenderingHandler;
import cpw.mods.fml.client.registry.RenderingRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import dan200.QCraft;
import dan200.qcraft.shared.*;
import net.minecraft.block.Block;
import net.minecraft.block.BlockDirectional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.particle.EntityFX;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.client.IItemRenderer;
import net.minecraftforge.client.MinecraftForgeClient;
import net.minecraftforge.common.MinecraftForge;
import org.lwjgl.opengl.GL11;

/*
    Sequence:

    ServerTools.teleportPlayerToDestinationServer
    ServerTools.tellClientToTeleportToRemoteServer
    ClientTools.onOriginServerStartedTeleportation
    ClientTools.travelToServer
    ServerTools.onPlayerLogout
    ServerTools.clearUnverifiedLuggage
    ServerTools.onPlayerLogin
    ServerTools.clearUnverifiedLuggage
    ServerTools.requestLuggageFromClient
    ClientTools.onDestinationServerRequestedLuggage
    ClientTools.tellServerToUnpackLuggage
    ServerTools.onClientRequestedUnpackLuggage
    ServerTools.unpackLuggage
    ServerTools.verifyIncomingLuggage
    ServerTools.tellClientToDiscardLuggage
    ClientTools.onServerDiscardedLuggage
    ServerTools.teleportPlayerToDestinationStargate
    ServerTools.onPlayerLogout
    ServerTools.clearUnverifiedLuggage
*/

public class ServerTraversalTools {
    public static class ClientTools {
        public static void travelToServer( String ipAddress )
        {
            // Disconnect from current server
            Minecraft minecraft = Minecraft.getMinecraft();
            minecraft.theWorld.sendQuittingDisconnectingPacket();
            minecraft.loadWorld((WorldClient)null);
            ServerData serverData = new ServerData( "qCraft Transfer", ipAddress );
            FMLClientHandler.instance().setupServerList();
            FMLClientHandler.instance().connectToServer( new GuiMainMenu(), serverData );
        }

        // Triggers ServerTools.onClientRequestedUnpackLuggage
        public static void tellServerToUnpackLuggage( LostLuggage.LuggageMatch luggage );

        // TODO: Make sure this works
        public static String getCurrentServerAddress()
        {
            ServerData serverData = FMLClientHandler.instance().getServerData();
            if( serverData != null )
            {
                String host = serverData.serverIP;
                int port = serverData.serverPort;
                return host + ":" + port;
            }
            // TODO: Throw the correct exception
            throw new RuntimeException( "No server data" );
        }

        public static void onOriginServerStartedTeleportation(String destinationIpAddress, byte[] luggage) {
            // Store luggage
            LostLuggage.Instance.load();
            LostLuggage.Instance.storeLuggage(
                getCurrentServerAddress(),
                destinationAddress,
                luggage
            );
            LostLuggage.Instance.save();

            travelToServer(destinationIpAddress);
        }

        public static void onDestinationServerRequestedLuggage() {
            // Finished spawning, server is ready to receive luggage
            String address = getCurrentServerAddress();
            if( address != null )
            {
                LostLuggage.Instance.load();
                LostLuggage.Instance.removeOldLuggage();
                Collection<LostLuggage.LuggageMatch> luggages = LostLuggage.Instance.getMatchingLuggage( address );
                if( luggages.size() > 0 )
                {
                    // Upload luggage to server
                    for( LostLuggage.LuggageMatch match : luggages )
                    {
                        tellServerToUnpackLuggage( match );
                    }
                }
                LostLuggage.Instance.save();
            }
        }
        
        public static void onServerDiscardedLuggage(byte[] luggage) {
            // Luggage was sent to server, who consumed it, and want you to get rid of it
            LostLuggage.Instance.load();
            LostLuggage.Instance.removeLuggage( luggage );
            LostLuggage.Instance.save();
        }

        public static class LostLuggage
        {
            public static final long MAX_LUGGAGE_AGE_HOURS = 24; // Luggage lasts 24 hours
            public static final LostLuggage Instance = new LostLuggage();

            public static class Address
            {
                private final String m_address;

                public Address( String address )
                {
                    m_address = address;
                }

                @Override
                public boolean equals( Object o )
                {
                    if( o == this )
                    {
                        return true;
                    }
                    if( o != null && o instanceof Address )
                    {
                        Address other = (Address)o;
                        return other.m_address.equals( m_address );
                    }
                    return false;
                }

                public String getAddress()
                {
                    return m_address;
                }
            }

            private static class Luggage
            {
                public long m_timeStamp;
                public Address m_origin; // Can be null, if portalling from a single player game
                public Address m_destination;
                public byte[] m_luggage;
            }

            public static class LuggageMatch
            {
                public boolean m_matchedDestination; // If this is false, the origin matched
                public byte[] m_luggage;
                public long m_timeStamp;

                public LuggageMatch( boolean matchedDestination, byte[] luggage, long timeStamp )
                {
                    m_matchedDestination = matchedDestination;
                    m_luggage = luggage;
                    m_timeStamp = timeStamp;
                }
            }

            private Set<Luggage> m_luggage;

            public LostLuggage()
            {
                m_luggage = new HashSet<Luggage>();
            }

            public void reset()
            {
                m_luggage.clear();
            }

            public void load()
            {
                File location = new File( "./qcraft/luggage.bin" );
                NBTTagCompound nbt = QCraftProxyCommon.loadNBTFromPath( location );
                if( nbt != null )
                {
                    readFromNBT( nbt );
                }
                else
                {
                    reset();
                }
            }

            public void save()
            {
                File location = new File( "./qcraft/luggage.bin" );
                NBTTagCompound nbt = new NBTTagCompound();
                writeToNBT( nbt );
                QCraftProxyCommon.saveNBTToPath( location, nbt );
            }

            private void readFromNBT( NBTTagCompound nbt )
            {
                m_luggage.clear();
                NBTTagList luggageList = nbt.getTagList( "luggage", 10 );
                for( int i=0; i<luggageList.tagCount(); ++i )
                {
                    NBTTagCompound luggageTag = luggageList.getCompoundTagAt( i );
                    Luggage luggage = new Luggage();
                    luggage.m_timeStamp = luggageTag.getLong( "timeStamp" );
                    if( luggageTag.hasKey( "originIP" ) && luggageTag.hasKey( "originPort" ) )
                    {
                        luggage.m_origin = new Address( luggageTag.getString( "originIP" ) + ":" + luggageTag.getInteger( "originPort" ) );
                    }
                    else if( luggageTag.hasKey( "originAddress" ) )
                    {
                        luggage.m_origin = new Address( luggageTag.getString( "originAddress" ) );
                    }
                    if( luggageTag.hasKey( "destinationIP" ) && luggageTag.hasKey( "destinationPort" ) )
                    {
                        luggage.m_destination = new Address( luggageTag.getString( "destinationIP" ) + ":" + luggageTag.getInteger( "destinationPort" ) );
                    }
                    else if( luggageTag.hasKey( "destinationAddress" ) )
                    {
                        luggage.m_destination = new Address( luggageTag.getString( "destinationAddress" ) );
                    }
                    luggage.m_luggage = luggageTag.getByteArray( "luggage" );
                    m_luggage.add( luggage );
                }
            }

            private void writeToNBT( NBTTagCompound nbt )
            {
                NBTTagList luggageList = new NBTTagList();
                for( Luggage luggage : m_luggage )
                {
                    NBTTagCompound luggageTag = new NBTTagCompound();
                    luggageTag.setLong( "timeStamp", luggage.m_timeStamp );
                    if( luggage.m_origin != null )
                    {
                        luggageTag.setString( "originAddress", luggage.m_origin.getAddress() );
                    }
                    if( luggage.m_destination != null )
                    {
                        luggageTag.setString( "destinationAddress", luggage.m_destination.getAddress() );
                    }
                    luggageTag.setByteArray( "luggage", luggage.m_luggage );
                    luggageList.appendTag( luggageTag );
                }
                nbt.setTag( "luggage", luggageList );
            }

            public void storeLuggage( Address origin, Address destination, byte[] luggageData )
            {
                Luggage luggage = new Luggage();
                luggage.m_timeStamp = System.currentTimeMillis(); // Yep, this is a UTC timestamp
                luggage.m_origin = origin;
                luggage.m_destination = destination;
                luggage.m_luggage = luggageData;
                m_luggage.add( luggage );
            }

            public void removeOldLuggage()
            {
                long timeNow = System.currentTimeMillis();
                Iterator<Luggage> it = m_luggage.iterator();
                while( it.hasNext() )
                {
                    Luggage luggage = it.next();
                    long ageMillis = timeNow - luggage.m_timeStamp;
                    if( ageMillis >= MAX_LUGGAGE_AGE_HOURS * 60 * 60 * 1000 )
                    {
                        it.remove();
                    }
                }
            }

            public Collection<LuggageMatch> getMatchingLuggage( Address server )
            {
                List<LuggageMatch> luggages = new ArrayList<LuggageMatch>();
                for( Luggage luggage : m_luggage )
                {
                    if( server.equals( luggage.m_destination ) )
                    {
                        luggages.add( new LuggageMatch( true, luggage.m_luggage, luggage.m_timeStamp ) );
                    }
                    else if( server.equals( luggage.m_origin ) )
                    {
                        luggages.add( new LuggageMatch( false, luggage.m_luggage, luggage.m_timeStamp ) );
                    }
                }
                return luggages;
            }

            public void removeLuggage( byte[] luggageData )
            {
                Iterator<Luggage> it = m_luggage.iterator();
                while( it.hasNext() )
                {
                    Luggage luggage = it.next();
                    if( Arrays.equals( luggageData, luggage.m_luggage ) )
                    {
                        it.remove();
                    }
                }
            }
        }

    }

    
    public static class ServerTools {

        // Triggers ClientTools.onOriginServerStartedTeleportation
        public static void tellClientToTeleportToRemoteServer(EntityPlayer player, String remoteIpAddress, byte[] luggage);

        // Triggers: ClientTools.onServerDiscardedLuggage()
        public static void tellClientToDiscardLuggage(EntityPlayer player, byte[] luggage);

        // Triggers: ClientTools.onDestinationServerRequestedLuggage()
        public static void requestLuggageFromClient(EntityPlayer player);        

        // Forge event responses

        @SubscribeEvent
        public void onPlayerLogin( PlayerEvent.PlayerLoggedInEvent event )
        {
            EntityPlayer player = event.player;
            if( FMLCommonHandler.instance().getEffectiveSide() == Side.SERVER )
            {
                clearUnverifiedLuggage( player ); // Shouldn't be necessary, but can't hurt
                requestLuggageFromClient( player );
            }
        }

        @SubscribeEvent
        public void onPlayerLogout( PlayerEvent.PlayerLoggedOutEvent event )
        {
            EntityPlayer player = event.player;
            if( FMLCommonHandler.instance().getEffectiveSide() == Side.SERVER )
            {
                clearUnverifiedLuggage( player );
            }
        }

        private static Map<String, Set<byte[]>> s_unverifiedLuggage = new HashMap<String, Set<byte[]>>();
    
        public static void addUnverifiedLuggage( EntityPlayer player, byte[] luggage )
        {
            String username = player.getCommandSenderName();
            if( !s_unverifiedLuggage.containsKey( username ) )
            {
                s_unverifiedLuggage.put( username, new HashSet<byte[]>() );
            }
    
            Set<byte[]> luggageSet = s_unverifiedLuggage.get( username );
            if( !luggageSet.contains( luggage ) )
            {
                luggageSet.add( luggage );
            }
        }
    
        public static void clearUnverifiedLuggage( EntityPlayer player )
        {
            String username = player.getCommandSenderName();
            if( s_unverifiedLuggage.containsKey( username ) )
            {
                s_unverifiedLuggage.remove( username );
            }
        }
    
        public static void log( String text )
        {
            FMLLog.info("[qCraft] " + text, 0); //Use FML logger instead of Vanilla MC log
        }

        // Teleport with inventory
        public static void teleportPlayerToDestinationServer( EntityPlayer player, String remoteServerAddress, String remoteStargateAddress, boolean takeItems )
        {
            // Log the trip
            log( "Sending player " + player.getDisplayName() + " to server \"" + remoteServerAddress + "\"" );

            NBTTagCompound luggage = new NBTTagCompound();
            if( takeItems )
            {
                // Remove and encode the items from the players inventory we want them to take with them
                NBTTagList items = new NBTTagList();
                InventoryPlayer playerInventory = player.inventory;
                for( int i = 0; i < playerInventory.getSizeInventory(); ++i )
                {
                    ItemStack stack = playerInventory.getStackInSlot( i );
                    if( stack != null && stack.stackSize > 0 )
                    {
                        // Store items
                        NBTTagCompound itemTag = new NBTTagCompound();
                        if (stack.getItem() == QCraft.Items.missingItem) {
                            itemTag = stack.stackTagCompound;
                        } else {
                            GameRegistry.UniqueIdentifier uniqueId = GameRegistry.findUniqueIdentifierFor(stack.getItem());
                            String itemName = uniqueId.modId + ":" + uniqueId.name;
                            itemTag.setString("Name", itemName);
                            stack.writeToNBT( itemTag );
                        }
                        items.appendTag( itemTag );

                        // Remove items
                        playerInventory.setInventorySlotContents( i, null );
                    }
                }

                if( items.tagCount() > 0 )
                {
                    log( "Removed " + items.tagCount() + " items from " + player.getDisplayName() + "'s inventory." );
                    playerInventory.markDirty();
                    luggage.setTag( "items", items );
                }
            }

            // Set the destination stargate address
            if( remoteStargateAddress != null )
            {
                luggage.setString( "destinationStargateAddress", remoteStargateAddress );
            }

            try
            {
                // Cryptographically sign the luggage
                luggage.setString( "uuid", UUID.randomUUID().toString() );
                byte[] luggageData = CompressedStreamTools.compress( luggage );
                byte[] luggageSignature = EncryptionRegistry.Instance.signData( luggageData );
                NBTTagCompound signedLuggage = new NBTTagCompound();
                signedLuggage.setByteArray( "key", EncryptionRegistry.Instance.encodePublicKey( EncryptionRegistry.Instance.getLocalKeyPair().getPublic() ) );
                signedLuggage.setByteArray( "luggage", luggageData );
                signedLuggage.setByteArray( "signature", luggageSignature );

                // Send the player to the remote server with the luggage
                byte[] signedLuggageData = CompressedStreamTools.compress( signedLuggage );
                tellClientToTeleportToRemoteServer(player, remoteServerAddress, signedLuggageData);
                
            }
            catch( IOException e )
            {
                throw new RuntimeException( "Error encoding inventory" );
            }
            finally
            {
                // Prevent the player from being warped twice
                player.timeUntilPortal = 200;
            }
        }

    public static void onClientRequestedUnpackLuggage(EntityPlayer entityPlayer, byte[] signedLuggageData, boolean isDestination) {
        // Connected from another server, took luggage with them
        boolean alreadyTeleported = false;
        boolean forceVerify = false;
        unpackLuggage(entityPlayer, entityPlayer, signedLuggageData, isDestination, alreadyTeleported, forceVerify);
    }

    public static Vec3 getPositionFromStargateAddress(String stargateAddress);
    public static Vec3 getDimensionFromStargateAddress(String stargateAddress);

    public static class StargateTeleporter extends Teleporter
    {
        private double m_xPos;
        private double m_yPos;
        private double m_zPos;
    
        public StargateTeleporter( WorldServer server, double xPos, double yPos, double zPos )
        {
            super( server );
            m_xPos = xPos;
            m_yPos = yPos;
            m_zPos = zPos;
        }
    
        @Override
        public void placeInPortal( Entity entity, double par2, double par4, double par6, float par8 )
        {
            if( entity instanceof EntityPlayer )
            {
                ((EntityPlayer)entity).setPositionAndUpdate( m_xPos, m_yPos, m_zPos );
                entity.motionX = entity.motionY = entity.motionZ = 0.0;
            }
        }
    
        @Override
        public boolean placeInExistingPortal(Entity par1Entity, double par2, double par4, double par6, float par8)
        {
            return false;
        }
    
        @Override
        public boolean makePortal(Entity par1Entity)
        {
            return false;
        }
    
        @Override
        public void removeStalePortalLocations(long par1)
        {
        }
    }

    public static void teleportPlayerToDestinationStargate(EntityPlayer player, String address)
    {
        Vec3 location = (address != null) ? getPositionFromStargateAddress( address ) : null;
        Vec3 dimension = (address != null) ? getDimensionFromStargateAddress( address ) : null;
        

        if( location != null )
        {
            if( dimension == player.dimension )
            {
                player.timeUntilPortal = 40;
                player.setPositionAndUpdate( location.x, location.y, location.z );
            }
            else if( player instanceof EntityPlayerMP )
            {
                player.timeUntilPortal = 40;
                MinecraftServer.getServer().getConfigurationManager().transferPlayerToDimension(
                    (EntityPlayerMP)player,
                    dimension,
                    new StargateTeleporter(
                        MinecraftServer.getServer().worldServerForDimension( dimension ),
                        location.x, location.y, location.z
                    )
                );
            }
        }
    }

    private static enum LuggageVerificationResult
    {
        UNTRUSTED,
        TRUSTED,
        LOCALKEY
    }

        private static boolean unpackLuggage( EntityPlayer instigator, EntityPlayer entityPlayer, byte[] signedLuggageData, boolean isDestination, boolean alreadyTeleported, boolean forceVerify )
        {
            try
            {
                // Verify the luggage
                LuggageVerificationResult verificationResult = verifyIncomingLuggage( instigator, entityPlayer, signedLuggageData, forceVerify );
                if( verificationResult != LuggageVerificationResult.UNTRUSTED )
                {
                    // Decompress the luggage
                    NBTTagCompound signedLuggage = CompressedStreamTools.func_152457_a(signedLuggageData, NBTSizeTracker.field_152451_a);
                    byte[] luggageData = signedLuggage.getByteArray( "luggage" );
                    NBTTagCompound luggage = CompressedStreamTools.func_152457_a(luggageData, NBTSizeTracker.field_152451_a);

                    // Unpack items
                    if( luggage.hasKey( "items" ) )
                    {
                        // Notify
                        if( verificationResult == LuggageVerificationResult.LOCALKEY && !isDestination )
                        {
                            entityPlayer.addChatMessage( new ChatComponentText(
                                "Previous attempted Portal Link failed, restoring inventory."
                            ) );
                        }

                        // Place every item from the luggage into the inventory
                        NBTTagList items = luggage.getTagList( "items", 10 );
                        log( "Adding " + items.tagCount() + " items to " + entityPlayer.getDisplayName() + "'s inventory" );
                        for( int i=0; i<items.tagCount(); ++i )
                        {
                            NBTTagCompound itemNBT = items.getCompoundTagAt( i );
                            ItemStack stack = ItemStack.loadItemStackFromNBT( itemNBT );
                            
                            String oldName = itemNBT.getString("Name");
                            String newName = "";
                            if (stack != null) {
                                GameRegistry.UniqueIdentifier uniqueId = GameRegistry.findUniqueIdentifierFor(stack.getItem());
                                newName = uniqueId.modId + ":" + uniqueId.name;
                            }                        
                            if (! oldName.equals(newName)) {
                                GameRegistry.UniqueIdentifier oldUniqueId = new GameRegistry.UniqueIdentifier(oldName);
                                int newID = Item.getIdFromItem(GameRegistry.findItem(oldUniqueId.modId, oldUniqueId.name));
                                if (newID < 1) { //0 and -1 indicate an error, and lower IDs are even worse I guess :P                               
                                    stack = new ItemStack(QCraft.Items.missingItem);
                                    stack.stackTagCompound = itemNBT;//Wrap the item in the dummy item
                                } else {
                                    itemNBT.setShort("id", (short) newID);
                                    stack = ItemStack.loadItemStackFromNBT( itemNBT );
                                }
                            }
                            
                            if( !entityPlayer.inventory.addItemStackToInventory( stack ) )
                            {
                                entityPlayer.entityDropItem( stack, 1.5f );
                            }
                        }
                        entityPlayer.inventory.markDirty();
                    }

                    // Teleport to destination portal
                    if( !alreadyTeleported && luggage.hasKey( "destinationStargateAddress" ) )
                    {
                        if( verificationResult == LuggageVerificationResult.TRUSTED ||
                            (verificationResult == LuggageVerificationResult.LOCALKEY && isDestination) )
                        {
                            // Find destination stargate
                            String destination = luggage.getString( "destinationStargateAddress" );
                            Vec3 location = getPositionFromStargateAddress( destination );
                            if( location != null )
                            {
                                log( "Teleporting " + entityPlayer.getDisplayName() + " to stargate \"" + destination + "\"" );
                                teleportPlayerToDestinationStargate( entityPlayer, destination );
                            }
                            else
                            {
                                entityPlayer.addChatMessage( new ChatComponentText( "Stargate Link failed:" ) );
                                entityPlayer.addChatMessage( new ChatComponentText( "There is no stargate on this server called \"" + destination + "\"" ) );
                            }
                            alreadyTeleported = true;
                        }
                    }
                }
                return alreadyTeleported;
            }
            catch( IOException e )
            {
                throw new RuntimeException( "Error unpacking luggage" );
            }
        }

        private static LuggageVerificationResult verifyIncomingLuggage( EntityPlayer instigator, EntityPlayer entityPlayer, byte[] signedLuggageData, boolean forceVerify ) throws IOException
        {
            NBTTagCompound signedLuggage = CompressedStreamTools.func_152457_a( signedLuggageData , NBTSizeTracker.field_152451_a);
            byte[] luggageData = signedLuggage.getByteArray("luggage");
            NBTTagCompound luggage = CompressedStreamTools.func_152457_a(luggageData, NBTSizeTracker.field_152451_a);
    
            if( signedLuggage.hasKey( "key" ) )
            {
                boolean luggageFromLocalServer = false;
    
                // Extract the key
                PublicKey key = EncryptionRegistry.Instance.decodePublicKey( signedLuggage.getByteArray( "key" ) );
                if( EncryptionRegistry.Instance.getLocalKeyPair().getPublic().equals(key) )
                {
                    log( "Player " + entityPlayer.getDisplayName() + " has luggage from this server." );
                    luggageFromLocalServer = true;
                }
                else if( !EncryptionRegistry.Instance.getVerifiedPublicKeys().contains(key) )
                {
                    // Key is unknown, link needs to be verified
                    // Verify link:
                    if( forceVerify )
                    {
                        log( "Player " + entityPlayer.getDisplayName() + " has luggage from unverified server. Verifying." );
                        EncryptionRegistry.Instance.getVerifiedPublicKeys().add( key );
                        instigator.addChatMessage( new ChatComponentText(
                            "Portal Link verified."
                        ) );
                        if( instigator != entityPlayer )
                        {
                            instigator.addChatMessage( new ChatComponentText(
                                "Portal Link verified"
                            ) );
                        }
                        EncryptionSavedData.get(getDefWorld()).markDirty(); //Notify that this needs to be saved on world save
                    }
                    else
                    {
                        log( "Player " + entityPlayer.getDisplayName() + " has luggage from unverified server. Ignoring." );
                        entityPlayer.addChatMessage( new ChatComponentText(
                            "Portal Link failed:"
                        ) );
                        if( QCraft.canAnybodyVerifyPortalServers() )
                        {
                            if( QCraft.canEverybodyVerifyPortalServers() )
                            {
                                entityPlayer.addChatMessage( new ChatComponentText(
                                    "The server link must be verified first."
                                ) );
                            }
                            else
                            {
                                entityPlayer.addChatMessage( new ChatComponentText(
                                    "The server link must be verified by an admin first."
                                ) );
                            }
                            addUnverifiedLuggage( entityPlayer, signedLuggageData );
                        }
                        else
                        {
                            entityPlayer.addChatMessage( new ChatComponentText(
                                "This server does not allow incoming inter-server portals."
                            ) );
                        }
    
                        boolean canVerify = QCraft.canPlayerVerifyPortalServers( entityPlayer );
                        boolean hasItems = luggage.hasKey( "items" );
                        if( canVerify && hasItems )
                        {
                            entityPlayer.addChatMessage( new ChatComponentText(
                                "Type \"/qcraft verify\" to do this now, or " +
                                "return to the original server within " + LostLuggage.MAX_LUGGAGE_AGE_HOURS + " hours to get your items back."
                            ) );
                        }
                        else if( canVerify )
                        {
                            entityPlayer.addChatMessage( new ChatComponentText(
                                "Type \"/qcraft verify\" to do this now."
                            ) );
                        }
                        else if( hasItems )
                        {
                            entityPlayer.addChatMessage( new ChatComponentText(
                                "Return to the original server within " + LostLuggage.MAX_LUGGAGE_AGE_HOURS + " hours to get your items back."
                            ) );
                        }
                        return LuggageVerificationResult.UNTRUSTED;
                    }
                }
                else
                {
                    log( "Player " + entityPlayer.getDisplayName() + " has luggage from verified server." );
                }
    
                // Verify against key
                byte[] luggageSignature = signedLuggage.getByteArray("signature");
                if( !EncryptionRegistry.Instance.verifyData( key, luggageSignature, luggageData ) )
                {
                    log( "Player " + entityPlayer.getDisplayName() + "'s luggage failed signature check. Ignoring." );
                    entityPlayer.addChatMessage( new ChatComponentText( "Portal Link failed:" ) );
                    entityPlayer.addChatMessage( new ChatComponentText( "Signature violation." ) );
                    tellClientToDiscardLuggage( entityPlayer, signedLuggageData );
                    return LuggageVerificationResult.UNTRUSTED;
                }
    
                // Check UUID is not used before
                UUID uuid = UUID.fromString( luggage.getString( "uuid" ) );
                if( EncryptionRegistry.Instance.getReceivedLuggageIDs().contains( uuid ) )
                {
                    log( "Player " + entityPlayer.getDisplayName() + "'s luggage is a duplicate. Ignoring." );
                    entityPlayer.addChatMessage( new ChatComponentText( "Portal Link failed:" ) );
                    entityPlayer.addChatMessage( new ChatComponentText( "Luggage duplicate." ) );
                    tellClientToDiscardLuggage( entityPlayer, signedLuggageData );
                    return LuggageVerificationResult.UNTRUSTED;
                }
                EncryptionRegistry.Instance.getReceivedLuggageIDs().add( uuid );
                EncryptionSavedData.get(getDefWorld()).markDirty(); //Notify that this needs to be saved on world save
    
                // We're ok
                tellClientToDiscardLuggage( entityPlayer, signedLuggageData );
                if( luggageFromLocalServer )
                {
                    return LuggageVerificationResult.LOCALKEY;
                }
                else
                {
                    return LuggageVerificationResult.TRUSTED;
                }
            }
            else
            {
                entityPlayer.addChatMessage( new ChatComponentText( "Portal Link failed:" ) );
                entityPlayer.addChatMessage( new ChatComponentText( "Signature missing." ) );
                tellClientToDiscardLuggage( entityPlayer, signedLuggageData );
                return LuggageVerificationResult.UNTRUSTED;
            }
        }

        public static class EncryptionRegistry
        {
            public static final EncryptionRegistry Instance = new EncryptionRegistry();

            // Privates
            private KeyPair m_localKeyPair;
            private Set<PublicKey> m_verifiedPublicKeys; // The public keys of all the servers we trust players from
            private Set<UUID> m_receivedLuggageIDs; // The UUID of every message we've ever received, to prevent repeat attacks

            // Methods
            public EncryptionRegistry()
            {
                reset();
            }

            public void reset()
            {
                m_localKeyPair = generateKeyPair();
                m_verifiedPublicKeys = new HashSet<PublicKey>();
                m_receivedLuggageIDs = new HashSet<UUID>();
            }

            public void readFromNBT( NBTTagCompound nbt )
            {
                PublicKey localPublicKey = decodePublicKey( nbt.getByteArray( "localPublicKey" ) );
                PrivateKey localPrivateKey = decodePrivateKey( nbt.getByteArray( "localPrivateKey" ) );
                m_localKeyPair = new KeyPair( localPublicKey, localPrivateKey );

                if( nbt.hasKey( "verifiedPublicKeys" ) )
                {
                    NBTTagList verifiedPublicKeys = nbt.getTagList( "verifiedPublicKeys", 10 );
                    for( int i=0; i<verifiedPublicKeys.tagCount(); ++i )
                    {
                        NBTTagCompound key = verifiedPublicKeys.getCompoundTagAt( i );
                        m_verifiedPublicKeys.add( decodePublicKey( key.getByteArray( "publicKey" ) ) );
                    }
                }

                if( nbt.hasKey( "receivedLuggageIDs" ) )
                {
                    NBTTagList receivedLuggageIDs = nbt.getTagList( "receivedLuggageIDs", 10 );
                    for( int i=0; i<receivedLuggageIDs.tagCount(); ++i )
                    {
                        NBTTagCompound key = receivedLuggageIDs.getCompoundTagAt( i );
                        m_receivedLuggageIDs.add( UUID.fromString( key.getString( "uuid" ) ) );
                    }
                }
            }

            public void writeToNBT( NBTTagCompound nbt )
            {
                nbt.setByteArray( "localPublicKey", encodePublicKey( m_localKeyPair.getPublic() ) );
                nbt.setByteArray( "localPrivateKey", encodePrivateKey( m_localKeyPair.getPrivate() ) );

                NBTTagList knownPublicKeys = new NBTTagList();
                for( PublicKey publicKey : m_verifiedPublicKeys )
                {
                    NBTTagCompound key = new NBTTagCompound();
                    key.setByteArray( "publicKey", encodePublicKey( publicKey ) );
                    knownPublicKeys.appendTag( key );
                }
                nbt.setTag( "verifiedPublicKeys", knownPublicKeys );

                NBTTagList receivedLuggageIDs = new NBTTagList();
                for( UUID uuid : m_receivedLuggageIDs )
                {
                    NBTTagCompound key = new NBTTagCompound();
                    key.setString( "uuid", uuid.toString() );
                    receivedLuggageIDs.appendTag( key );
                }
                nbt.setTag( "receivedLuggageIDs", receivedLuggageIDs );
            }

            public KeyPair getLocalKeyPair()
            {
                return m_localKeyPair;
            }

            public Set<PublicKey> getVerifiedPublicKeys()
            {
                return m_verifiedPublicKeys;
            }

            public Set<UUID> getReceivedLuggageIDs()
            {
                return m_receivedLuggageIDs;
            }

            public byte[] encodePublicKey( PublicKey key )
            {
                return new X509EncodedKeySpec( key.getEncoded() ).getEncoded();
            }

            public PublicKey decodePublicKey( byte[] encodedKey )
            {
                try
                {
                    KeyFactory keyFactory = KeyFactory.getInstance( "DSA" );
                    return keyFactory.generatePublic( new X509EncodedKeySpec( encodedKey ) );
                }
                catch( Exception e )
                {
                    System.out.println( "QCraft: decoding key failed with exception: " + e.toString() );
                    return null;
                }
            }

            public byte[] encodePrivateKey( PrivateKey key )
            {
                return new PKCS8EncodedKeySpec( key.getEncoded() ).getEncoded();
            }

            public PrivateKey decodePrivateKey( byte[] encodedKey )
            {
                try
                {
                    KeyFactory keyFactory = KeyFactory.getInstance( "DSA" );
                    return keyFactory.generatePrivate( new PKCS8EncodedKeySpec( encodedKey ) );
                }
                catch( Exception e )
                {
                    QCraft.log( "QCraft: Decoding key failed with exception: " + e.toString() );
                    return null;
                }
            }

            public byte[] signData( byte[] message )
            {
                try
                {
                    Signature signature = Signature.getInstance( "SHA1withDSA", "SUN" );        // generate a signature
                    signature.initSign( m_localKeyPair.getPrivate() );
                    signature.update( message );
                    return signature.sign();
                }
                catch( Exception ex )
                {
                    QCraft.log( "QCraft: Signing data failed with exception: " + ex.toString() );
                }
                return null;
            }

            public boolean verifyData( PublicKey verifyKey, byte[] digest, byte[] message )
            {
                try
                {
                    // verify a signature
                    Signature signature = Signature.getInstance( "SHA1withDSA", "SUN" );
                    signature.initVerify( verifyKey );
                    signature.update( message );

                    if( signature.verify( digest ) )
                    {
                        return true;
                    }
                    else
                    {
                        return false;
                    }
                }
                catch( Exception e )
                {
                    QCraft.log( "QCraft: Verifying data failed with exception: " + e.toString() );
                }
                return false;
            }

            private static KeyPair generateKeyPair()
            {
                try
                {
                    KeyPairGenerator keyGen = KeyPairGenerator.getInstance( "DSA", "SUN" );
                    SecureRandom random = SecureRandom.getInstance( "SHA1PRNG", "SUN" );
                    keyGen.initialize( 1024, random );
                    return keyGen.generateKeyPair();
                }
                catch( Exception ex )
                {
                    QCraft.log( "QCraft: Generating keypair failed with exception: " + ex.toString() );
                }
                return null;
            }
        }

         /**
         *
         * @author Robijnvogel
         */
        public static class EncryptionSavedData extends QCraftSavedData {

            private static final String DATA_NAME = "qCraft_EncSavedData";

            public EncryptionSavedData() {
                super(DATA_NAME);
            }

            public EncryptionSavedData(String s) {
                super(s);
            }

            @Override
            public File getSaveLocation(World world) {
                return new File(super.getSaveLocation(world), "encryption.bin");
            }

            public static EncryptionSavedData get(World world) {
                MapStorage storage = world.mapStorage;
                EncryptionSavedData instance = (EncryptionSavedData) storage.loadData(EncryptionSavedData.class, DATA_NAME);

                if (instance == null) {
                    instance = new EncryptionSavedData();
                    storage.setData(DATA_NAME, instance);
                }
                return instance;
            }

            @Override
            public void writeToNBT(NBTTagCompound encryptionnbt) {

                NBTTagCompound encryption = new NBTTagCompound();
                EncryptionRegistry.Instance.writeToNBT(encryption);
                encryptionnbt.setTag("encryption", encryption);

                saveNBTToPath(getSaveLocation(QCraft.getDefWorld()), encryptionnbt);
            }

            @Override
            public void readFromNBT(NBTTagCompound encryptionnbt) {
                // Reset
                EncryptionRegistry.Instance.reset();

                // Load NBT
                if (encryptionnbt != null) {
                    if (encryptionnbt.hasKey("encryption")) {
                        NBTTagCompound encryption = encryptionnbt.getCompoundTag("encryption");
                        EncryptionRegistry.Instance.readFromNBT(encryption);
                    }
                }
            }
        }
         /**
         *
         * @author Robijnvogel
         */
        abstract class QCraftSavedData extends WorldSavedData {

            public QCraftSavedData(String name) {
                super(name);
            }

            public File getSaveLocation(World world) {
                File rootDir = FMLCommonHandler.instance().getMinecraftServerInstance().getFile( "." );
                File saveDir = null;
                if( QCraft.isServer() )
                {
                    saveDir = new File( rootDir, world.getSaveHandler().getWorldDirectoryName() );
                }
                else
                {
                    saveDir = new File( rootDir, "saves/" + world.getSaveHandler().getWorldDirectoryName() );
                }
                return new File( saveDir, "quantum/" );
            }
        }
    }
}
