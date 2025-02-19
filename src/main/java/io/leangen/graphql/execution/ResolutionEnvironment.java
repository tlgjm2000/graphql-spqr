package io.leangen.graphql.execution;

import graphql.ErrorType;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.execution.ExecutionStepInfo;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLOutputType;
import graphql.schema.GraphQLSchema;
import io.leangen.graphql.generator.mapping.ArgumentInjectorParams;
import io.leangen.graphql.generator.mapping.ConverterRegistry;
import io.leangen.graphql.generator.mapping.DelegatingOutputConverter;
import io.leangen.graphql.generator.mapping.OutputConverter;
import io.leangen.graphql.metadata.OperationArgument;
import io.leangen.graphql.metadata.Resolver;
import io.leangen.graphql.metadata.strategy.value.ValueMapper;
import io.leangen.graphql.util.ContextUtils;
import io.leangen.graphql.util.Urls;
import org.dataloader.BatchLoaderEnvironment;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.AnnotatedType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Bojan Tomic (kaqqao)
 */
@SuppressWarnings("WeakerAccess")
public class ResolutionEnvironment {

    public final Object context;
    public final Object rootContext;
    public final Object batchContext;
    public final Resolver resolver;
    public final ValueMapper valueMapper;
    public final GlobalEnvironment globalEnvironment;
    public final GraphQLOutputType fieldType;
    public final GraphQLNamedType parentType;
    public final GraphQLSchema graphQLSchema;
    public final DataFetchingEnvironment dataFetchingEnvironment;
    public final BatchLoaderEnvironment batchLoaderEnvironment;
    public final Map<String, Object> arguments;
    public final List<GraphQLError> errors;

    private final ConverterRegistry converters;
    private final DerivedTypeRegistry derivedTypes;

    public ResolutionEnvironment(Resolver resolver, DataFetchingEnvironment env, ValueMapper valueMapper, GlobalEnvironment globalEnvironment,
                                 ConverterRegistry converters, DerivedTypeRegistry derivedTypes) {

        this.context = env.getSource();
        this.rootContext = env.getContext();
        this.batchContext = null;
        this.resolver = resolver;
        this.valueMapper = valueMapper;
        this.globalEnvironment = globalEnvironment;
        this.fieldType = env.getFieldType();
        this.parentType = (GraphQLNamedType) env.getParentType();
        this.graphQLSchema = env.getGraphQLSchema();
        this.dataFetchingEnvironment = env;
        this.batchLoaderEnvironment = null;
        this.arguments = new HashMap<>();
        this.errors = new ArrayList<>();
        this.converters = converters;
        this.derivedTypes = derivedTypes;
    }

    public ResolutionEnvironment(Resolver resolver, List<Object> keys, BatchLoaderEnvironment env, ValueMapper valueMapper, GlobalEnvironment globalEnvironment,
                                 ConverterRegistry converters, DerivedTypeRegistry derivedTypes) {

        DataFetchingEnvironment inner = dataFetchingEnvironment(env.getKeyContextsList());
        this.context = keys;
        this.rootContext = inner != null ? inner.getContext() : null;
        this.batchContext = env.getContext();
        this.resolver = resolver;
        this.valueMapper = valueMapper;
        this.globalEnvironment = globalEnvironment;
        this.fieldType = inner != null ? inner.getFieldType() : null;
        this.parentType = inner != null ? (GraphQLNamedType) inner.getParentType() : null;
        this.graphQLSchema = inner != null ? inner.getGraphQLSchema() : null;
        this.dataFetchingEnvironment = null;
        this.batchLoaderEnvironment = env;
        this.arguments = new HashMap<>();
        this.errors = new ArrayList<>();
        this.converters = converters;
        this.derivedTypes = derivedTypes;
    }

    public <T, S> S convertOutput(T output, AnnotatedElement element, AnnotatedType type) {
        if (output == null) {
            return null;
        }

        return convert(output, element, type);
    }

    @SuppressWarnings("unchecked")
    private <T, S> S convert(T output, AnnotatedElement element, AnnotatedType type) {
        OutputConverter<T, S> outputConverter = converters.getOutputConverter(element, type);
        return outputConverter == null ? (S) output : outputConverter.convertOutput(output, type, this);
    }

    public AnnotatedType getDerived(AnnotatedType type, int index) {
        try {
            return getDerived(type).get(index);
        } catch (IndexOutOfBoundsException e) {
            throw new RuntimeException(String.format("No type derived from %s found at index %d. " +
                            "Make sure the converter implements %s and provides the derived types correctly. " +
                            "See %s for details and possible solutions.",
                    type.getType().getTypeName(), index, DelegatingOutputConverter.class.getSimpleName(), Urls.Errors.DERIVED_TYPES), e);
        }
    }

    public List<AnnotatedType> getDerived(AnnotatedType type) {
        return derivedTypes.getDerived(type);
    }

    public Object getInputValue(Object input, OperationArgument argument) {
        boolean argValuePresent = dataFetchingEnvironment != null && dataFetchingEnvironment.containsArgument(argument.getName());
        ArgumentInjectorParams params = new ArgumentInjectorParams(input, argValuePresent, argument, this);
        Object value = this.globalEnvironment.injectors.getInjector(argument.getJavaType(), argument.getParameter()).getArgumentValue(params);
        if (argValuePresent) {
            arguments.put(argument.getName(), value);
        }
        return value;
    }

    public void addError(String message, Object... formatArgs) {
        this.errors.add(createError(message, formatArgs));
    }

    public GraphQLError createError(String message, Object... formatArgs) {
        GraphqlErrorBuilder<?> builder = dataFetchingEnvironment != null
                ? GraphqlErrorBuilder.newError(dataFetchingEnvironment)
                : GraphqlErrorBuilder.newError();
        return builder
                .message(message, formatArgs)
                .errorType(ErrorType.DataFetchingException)
                .build();
    }

    public Directives getDirectives(ExecutionStepInfo step) {
        return new Directives(dataFetchingEnvironment, step);
    }

    public Directives getDirectives() {
        return getDirectives(null);
    }

    public Object getGlobalContext() {
        return ContextUtils.unwrapContext(rootContext);
    }

    private static DataFetchingEnvironment dataFetchingEnvironment(List<Object> keyContexts) {
        if (keyContexts == null || keyContexts.isEmpty() || !(keyContexts.get(0) instanceof DataFetchingEnvironment)) {
            return null;
        }
        return (DataFetchingEnvironment) keyContexts.get(0);
    }
}
