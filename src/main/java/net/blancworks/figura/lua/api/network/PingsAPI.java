package net.blancworks.figura.lua.api.network;

import net.blancworks.figura.PlayerDataManager;
import net.blancworks.figura.lua.CustomScript;
import net.minecraft.util.Identifier;
import org.luaj.vm2.*;

public class PingsAPI {

    public static Identifier getID() {
        return new Identifier("default", "ping");
    }

    public static LuaTable getForScript(CustomScript script) {
        return new PingsAPILuaTable(script);
    }

    private static class PingsAPILuaTable extends LuaTable {
        private final CustomScript targetScript;

        private PingsAPILuaTable(CustomScript script) {
            this.targetScript = script;
        }

        @Override
        public void rawset(LuaValue key, LuaValue value) {
            //check if it is a valid ping
            if (!key.isstring() || !value.isfunction())
                throw new LuaError("Error while registering ping");

            //register ping
            targetScript.registerPing(value);

            super.rawset(key, new PingFunction(targetScript, value.checkfunction()));
        }
    }

    private static class PingFunction extends LuaFunction {
        private final CustomScript targetScript;
        private final LuaFunction func;

        public PingFunction(CustomScript script, LuaFunction func) {
            this.targetScript = script;
            this.func = func;
        }

        @Override
        public LuaValue call() {
            return call(NIL);
        }

        @Override
        public LuaValue call(LuaValue arg) {
            //only local player can send pings
            if (targetScript.playerData != PlayerDataManager.localPlayer)
                return NIL;

            try {
                //get ping ID from the function
                short id = targetScript.newFunctionIDMap.inverse().get(func);

                //local ping is handled immediately
                targetScript.handlePing(id, arg);

                //add outgoing ping
                CustomScript.LuaPing lp = new CustomScript.LuaPing();
                lp.args = arg;
                lp.functionID = id;

                if (!targetScript.playerData.isLocalAvatar)
                    targetScript.outgoingPingQueue.add(lp);
            } catch (Exception e) {
                e.printStackTrace();
                throw new LuaError("Something went wrong while sending ping!");
            }

            return NIL;
        }

        @Override
        public LuaValue call(LuaValue arg1, LuaValue arg2) {
            return call(arg1);
        }

        @Override
        public LuaValue call(LuaValue arg1, LuaValue arg2, LuaValue arg3) {
            return call(arg1);
        }

        @Override
        public Varargs invoke(Varargs args) {
            LuaValue[] val = {call(args.arg(1))};
            return varargsOf(val);
        }
    }
}
