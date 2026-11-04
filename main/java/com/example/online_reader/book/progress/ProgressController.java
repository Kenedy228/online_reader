package com.example.online_reader.book.progress;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.StreamReadConstraints;
import jakarta.validation.*;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;

@RestController
@RequestMapping("/api/v1/books/{id}/reading-progress")
public class ProgressController {
    public record Save(@NotNull @Min(0) @Max(2147483646) Integer revision,@NotNull @Valid ReadingPosition position,@NotBlank @Size(max=100) String proof) { }
    private final ProgressService service;private final ObjectReader json;private final Validator validator;
    public ProgressController(ProgressService service,ObjectMapper mapper,Validator validator) {
        this.service=service;this.validator=validator;var strict=mapper.copy();
        strict.coercionConfigFor(com.fasterxml.jackson.databind.type.LogicalType.Integer)
                .setCoercion(com.fasterxml.jackson.databind.cfg.CoercionInputShape.Float,com.fasterxml.jackson.databind.cfg.CoercionAction.Fail)
                .setCoercion(com.fasterxml.jackson.databind.cfg.CoercionInputShape.String,com.fasterxml.jackson.databind.cfg.CoercionAction.Fail);
        strict.coercionConfigFor(com.fasterxml.jackson.databind.type.LogicalType.Textual)
                .setCoercion(com.fasterxml.jackson.databind.cfg.CoercionInputShape.Integer,com.fasterxml.jackson.databind.cfg.CoercionAction.Fail)
                .setCoercion(com.fasterxml.jackson.databind.cfg.CoercionInputShape.Float,com.fasterxml.jackson.databind.cfg.CoercionAction.Fail)
                .setCoercion(com.fasterxml.jackson.databind.cfg.CoercionInputShape.Boolean,com.fasterxml.jackson.databind.cfg.CoercionAction.Fail);
        strict.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8).maxStringLength(256).maxNumberLength(12).build());
        json=strict.readerFor(Save.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS).with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }
    @GetMapping(produces=MediaType.APPLICATION_JSON_VALUE)
    public ProgressService.Snapshot get(@PathVariable Long id){positive(id);return service.load(id);}
    @PutMapping(consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ProgressService.Snapshot> save(@PathVariable Long id,@RequestBody byte[] body) {
        positive(id);if(body.length>2048)throw new ProgressService.Failure(413,"request_too_large");
        Save request;
        try {request=json.readValue(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString());}
        catch(java.io.IOException e){throw new ProgressService.Failure(400,"invalid_request");}
        if(request==null || !validator.validate(request).isEmpty())throw new ProgressService.Failure(422,"invalid_position");
        var result=service.save(id,request.position(),request.revision(),request.proof());
        return ResponseEntity.status(request.revision()==0?201:200).cacheControl(CacheControl.noStore()).body(result);
    }
    private static void positive(Long id){if(id<1)throw new ProgressService.Failure(400,"invalid_request");}
}
