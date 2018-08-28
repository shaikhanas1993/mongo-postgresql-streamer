package com.malt.mongopostgresqlstreamer;

import com.malt.mongopostgresqlstreamer.connectors.Connector;
import com.malt.mongopostgresqlstreamer.model.FlattenMongoDocument;
import com.mongodb.CursorType;
import com.mongodb.MongoClient;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import lombok.extern.slf4j.Slf4j;
import org.bson.BsonTimestamp;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.mongodb.client.model.Filters.*;

@Service
@Slf4j
public class OplogStreamer {

    @Value(value = "${mongo.connector.identifier:streamer}")
    private String identifier;

    @Value(value = "${mongo.database:test}")
    private String dbName;

    @Autowired
    private MappingsManager mappingsManager;
    @Autowired
    private CheckpointManager checkpointManager;
    @Autowired
    @Qualifier("oplog")
    private MongoDatabase oplog;
    @Autowired
    @Qualifier("database")
    private MongoDatabase adminDatabase;
    @Autowired
    private MongoClient mongoClient;
    @Autowired
    private List<Connector> connectors;

    public void watchFromCheckpoint(Optional<BsonTimestamp> checkpoint) {
        log.info("Start watching the oplog...");
        for (Document document : oplogDocuments(checkpoint)) {
            BsonTimestamp timestamp = processOperation(document);
            checkpointManager.keep(timestamp);
        }
    }

    private FindIterable<Document> oplogDocuments(Optional<BsonTimestamp> checkpoint) {
        MongoCollection<Document> oplog = this.oplog.getCollection("oplog.rs");
        if (checkpoint.isPresent()) {
            Document lastKnownOplog = oplog.find(eq("ts", checkpoint.get())).first();
            if (lastKnownOplog == null) {
                log.error("Last known oplog is not in the oplog anymore. The watch will starts from first " +
                        "oplog but you should consider relaunch a reimport");
                checkpoint = Optional.empty();
            }
        }
        return oplog.find(oplogfilters(checkpoint)).cursorType(CursorType.TailableAwait);
    }

    @Transactional
    BsonTimestamp processOperation(Document document) {
        String namespace = document.getString("ns");
        String[] databaseAndCollection = namespace.split("\\.");
        String collection = databaseAndCollection[1];
        String database = databaseAndCollection[0];
        String operation = document.getString("op");
        BsonTimestamp timestamp = document.get("ts", BsonTimestamp.class);

        mappingsManager.mappingConfigs.databaseMappingFor(database).ifPresent(mappings -> {
            MongoDatabase mongoDb = mongoClient.getDatabase(database);
            if (mappings.get(collection).isPresent()) {
                log.debug("Operation {} detected on {}", operation, namespace);
                switch (operation) {
                    case "i":
                        Map newDocument = (Map) document.get("o");
                        connectors.forEach(connector ->
                                connector.insert(
                                        collection,
                                        FlattenMongoDocument.fromMap(newDocument),
                                        mappings
                                )
                        );
                        break;
                    case "u":
                        Map documentIdToUpdate = (Map) document.get("o2");
                        Document updatedDocument = mongoDb.getCollection(collection)
                                .find(eq("_id", documentIdToUpdate.get("_id")))
                                .first();
                        if (updatedDocument != null) {
                            connectors.forEach(connector ->
                                    connector.update(
                                            collection,
                                            FlattenMongoDocument.fromMap(updatedDocument),
                                            mappings
                                    )
                            );
                        }
                        break;
                    case "d":
                        Map documentIdToRemove = (Map) document.get("o");
                        connectors.forEach(connector ->
                                connector.remove(
                                        collection,
                                        FlattenMongoDocument.fromMap(documentIdToRemove),
                                        mappings
                                )
                        );
                        break;
                    default:
                        break;
                }
            }
        });

        return timestamp;
    }

    private Bson oplogfilters(Optional<BsonTimestamp> checkpoint) {
        return checkpoint.map(bsonTimestamp -> and(
                in("ns", mappingsManager.mappedNamespaces()),
                gt("ts", bsonTimestamp),
                exists("fromMigrate", false),
                in("op", "d", "u", "i")))

                .orElseGet(() -> and(
                in("ns", mappingsManager.mappedNamespaces()),
                exists("fromMigrate", false),
                in("op", "d", "u", "i")));
    }


}
