package cdpai.netrec;

import com.fasterxml.jackson.databind.JsonNode;

public interface Route { JsonNode handle(JsonNode req) throws Exception; }
