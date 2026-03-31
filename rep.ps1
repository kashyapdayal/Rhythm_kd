$content = [System.IO.File]::ReadAllText('app/src/main/java/chromahub/rhythm/app/features/local/presentation/screens/settings/PlaceholderScreens.kt', [System.Text.Encoding]::UTF8)
$old = @"
                                onToggleChange = { appSettings.setPlayerShowAudioQualityBadges(it) }
                            )
                        )
                    ),
