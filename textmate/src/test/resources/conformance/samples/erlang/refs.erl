%% Reads refs from packed-refs.
-module(refs).
-export([parse/1, short/1]).

-define(HEADS, "refs/heads/").

-record(ref, {name :: string(), hash :: binary()}).

-spec parse(binary()) -> [#ref{}].
parse(Bin) ->
    Lines = binary:split(Bin, <<"\n">>, [global, trim]),
    [#ref{name = binary_to_list(Name), hash = Hash}
     || Line <- Lines, Line =/= <<>>, binary:first(Line) =/= $#,
        [Hash, Name] <- [binary:split(Line, <<" ">>)]].

short(#ref{name = ?HEADS ++ Short}) -> Short;
short(#ref{name = Name}) -> Name.

count(Refs) when is_list(Refs) -> length(Refs) + 16#0.
