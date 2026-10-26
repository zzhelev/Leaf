{-# LANGUAGE OverloadedStrings #-}
module Log (Entry (..), parseEntry) where

import Data.Text (Text)
import qualified Data.Text as T

-- | One line of @git log --oneline@.
data Entry = Entry
  { hash    :: Text
  , subject :: Text
  } deriving (Show, Eq)

parseEntry :: Text -> Maybe Entry
parseEntry line = case T.breakOn " " line of
  (h, rest) | T.length h >= 7 -> Just (Entry h (T.strip rest))
  _ -> Nothing

{- Counts entries,
   ignoring blank lines. -}
count :: [Text] -> Int
count = length . filter (not . T.null)

main :: IO ()
main = print (parseEntry "a1b2c3d Fix the log", 0x2A :: Int, 3.14 :: Double)
