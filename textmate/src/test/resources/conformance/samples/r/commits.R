# Commits per week, from a CSV of commit dates.
library(dplyr)

read_commits <- function(path, tz = "UTC") {
  commits <- read.csv(path, stringsAsFactors = FALSE)
  commits$date <- as.POSIXct(commits$date, tz = tz)
  commits
}

weekly <- read_commits("commits.csv") %>%
  mutate(week = format(date, "%Y-%W")) %>%
  count(week, name = "commits")

if (nrow(weekly) > 0L && !is.na(weekly$commits[1])) {
  print(summary(weekly$commits))
} else {
  message("No commits")
}
x <- c(1.5, 2e3, NA, TRUE, NULL)
